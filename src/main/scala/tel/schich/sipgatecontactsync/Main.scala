package tel.schich.sipgatecontactsync

import java.nio.file.{Files, Path, Paths}
import com.typesafe.scalalogging.StrictLogging
import io.circe.parser.*
import org.apache.directory.api.ldap.model.entry.*
import org.apache.directory.api.ldap.model.exception.{LdapException, LdapOperationException}
import org.apache.directory.api.ldap.model.message.SearchScope
import org.apache.directory.api.ldap.model.name.Dn
import org.apache.directory.ldap.client.api.{LdapConnectionConfig, LdapNetworkConnection}

import java.net.URI
import java.net.http.HttpResponse.BodyHandlers
import java.net.http.{HttpClient, HttpRequest}
import scala.collection.mutable
import scala.compat.java8.FutureConverters.CompletionStageOps
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}
import scala.concurrent.ExecutionContext.Implicits.*
import scala.io.Source
import scala.util.{Either, Left, Right}
import scala.jdk.CollectionConverters.*

object Main extends StrictLogging {

    private val SipgateContactsEndpoint = "https://api.sipgate.com/v2/contacts"
    //val SipgateContactsEndpoint = "http://localhost:4444/v2/contacts"

    private val ObjectClasses: Set[String] = Set("top", "inetOrgPerson")
    private val DnAttribute: String = "ou"
    private val AttributeMappings: Seq[(String, Contact => Either[String, Set[String]])] = Seq(
        ("objectClass", _ => Right(ObjectClasses)),
        (DnAttribute, c => Left(c.id)),
        ("cn", c => Left(c.trimmedName)),
        ("displayName", c => Left(c.trimmedName)),
        ("sn", c => Left(c.surname)),
        ("telephoneNumber", c => Right(c.numbers.map(_.number.e164).toSet)),
        ("o", c => Right(c.organization.flatMap(_.headOption).toSet)),
    )
    private val AttributeNames: Set[String] = AttributeMappings.map(_._1.toLowerCase).toSet

    private def connectAndBindToLdap(conf: SipgateImportConfig): Future[LdapNetworkConnection] = Future {
        val ldapConn = {
            val ldapConfig = new LdapConnectionConfig
            ldapConfig.setLdapHost(conf.ldapHost)
            ldapConfig.setLdapPort(conf.ldapPort)
            ldapConfig.setUseSsl(false)
            new LdapNetworkConnection(ldapConfig)
        }

        try {
            ldapConn.bind(conf.ldapBindUser, conf.ldapBindPassword)
        } catch {
            case e: LdapException =>
                logger.error(s"Bind failed: ${e.getLocalizedMessage}", e)
                ldapConn.close()
                throw e
        }

        ldapConn
    }

    private def readConfig(path: Path): SipgateImportConfig = {
        val source = Source.fromFile(path.toFile)
        try {
            val content = source.mkString
            parse(content).flatMap(_.as[SipgateImportConfig]).getOrElse(null)
        } finally {
            source.close()
        }
    }

    private def buildRequest(conf: SipgateImportConfig, limit: Int = 5000, offset: Int = 0): HttpRequest = {
        val separator = if (SipgateContactsEndpoint.contains('?')) '&' else '?'
        val fullUri = s"$SipgateContactsEndpoint${separator}limit=$limit&offset=$offset"
        HttpRequest.newBuilder(URI(fullUri))
          .header("Accept", "application/json")
          .headers("Authorization", s"${conf.sipgateAuth}")
          .GET()
          .build()
    }

    private def importContacts(conf: SipgateImportConfig, contacts: Seq[Contact], ldapConn: LdapNetworkConnection): Unit = {

        val baseDn = new Dn(conf.ldapBaseDn)

        val lookup = mutable.Map[String, Entry]()
        val cursor = ldapConn.search(baseDn, s"($DnAttribute=*)", SearchScope.ONELEVEL)
        while (cursor.next()) {
            val e = cursor.get()
            val id = e.get(DnAttribute).get().toString
            lookup += id -> e
        }

        for (contact <- contacts) {
            if (contact.hasName) {
                try {
                    lookup.get(contact.id) match {
                        case Some(existing) =>
                            if (updateEntry(ldapConn, existing, contact)) logger.info(s"Updated contact ${contact.id} (${contact.name})")
                            else logger.debug(s"Contact ${contact.id} (${contact.name}) is already up to date!")
                        case None =>
                            createNewEntry(ldapConn, baseDn, contact)
                            logger.info(s"Created new contact ${contact.id} (${contact.name}).")
                    }
                } catch {
                    case e: LdapException =>
                        logger.warn(s"Failed to process contact ${contact.id} (${contact.name}): ${e.getLocalizedMessage}", e)
                }
            }
        }

        val contactIds = contacts.map(_.id).toSet
        for ((id, entry) <- lookup if !contactIds.contains(id)) {
            ldapConn.delete(entry.getDn)
            logger.info(s"Deleted obsolete entry $id.")
        }

    }

    private def updateEntry(ldapConn: LdapNetworkConnection, entry: Entry, contact: Contact): Boolean = {

        val updates = AttributeMappings.flatMap {
            case (attributeName, mapping) =>
                mapping(contact) match {
                    case Left(value) =>
                        compareAndModify(entry, attributeName, value)
                    case Right(values) =>
                        compareAndModify(entry, attributeName, values)
                }
        }

        val removals = entry.getAttributes.asScala.map(_.getId).filterNot(AttributeNames.contains).map { attributeName =>
            new DefaultModification(ModificationOperation.REMOVE_ATTRIBUTE, attributeName)
        }

        val modifications = updates ++ removals

        if (modifications.nonEmpty) {
            logger.debug(s"$entry")
            modifications.foreach { m => logger.debug(s"$m") }
            ldapConn.modify(entry.getDn, modifications*)
            true
        } else false
    }

    private def compareAndModify(entry: Entry, attributeName: String, contactValue: String): Option[Modification] = {
        val attribute = entry.get(attributeName)
        if (attribute != null) {
            val ldapValue = attribute.get().toString
            if (ldapValue != contactValue) {
                if (contactValue.nonEmpty) Some(new DefaultModification(ModificationOperation.REPLACE_ATTRIBUTE, attributeName, contactValue)) // different value -> replace
                else Some(new DefaultModification(ModificationOperation.REMOVE_ATTRIBUTE, attributeName)) // empty value -> remove
            }
            else None // unchanged -> nop
        } else {
            Some(new DefaultModification(ModificationOperation.ADD_ATTRIBUTE, attributeName, contactValue)) // new attribute -> add
        }
    }

    private def compareAndModify(entry: Entry, attributeName: String, contactValues: Set[String]): Seq[Modification] = {
        val attribute = entry.get(attributeName)
        if (attribute != null) {
            if (contactValues.isEmpty) {
                Seq(new DefaultModification(ModificationOperation.REMOVE_ATTRIBUTE, attributeName)) // no values empty -> remove entire attribute
            } else {
                val ldapValues = attribute.iterator().asScala.map(_.toString).toSet
                val obsoleteValues = ldapValues.diff(contactValues).toSeq
                val newValues = contactValues.diff(ldapValues).toSeq

                val removal = if (obsoleteValues.nonEmpty) {
                    Seq(new DefaultModification(ModificationOperation.REMOVE_ATTRIBUTE, attributeName, obsoleteValues*)) // remove obsolete values
                } else Nil

                val addition = if (newValues.nonEmpty) {
                    Seq(new DefaultModification(ModificationOperation.ADD_ATTRIBUTE, attributeName, newValues*)) // add new values
                } else Nil

                removal ++ addition
            }
        } else {
            if (contactValues.nonEmpty) {
                Seq(new DefaultModification(ModificationOperation.ADD_ATTRIBUTE, attributeName, contactValues.toSeq*)) // no existing attribute -> add all new values
            } else Nil
        }
    }

    private def createNewEntry(ldapConn: LdapNetworkConnection, baseDn: Dn, contact: Contact): Unit = {
        val entry = new DefaultEntry()
        entry.setDn(baseDn.add(s"$DnAttribute=${contact.id}"))
        for ((attributeName, mapping) <- AttributeMappings) {
            mapping(contact) match {
                case Left(value) =>
                    if (value.nonEmpty) {
                        entry.add(attributeName, value)
                    }
                case Right(values) =>
                    if (values.nonEmpty) {
                        entry.add(attributeName, values.toSeq*)
                    }
            }
        }

        try {
            ldapConn.add(entry)
        } catch {
            case e: LdapOperationException =>
                logger.warn("Failed to add entry!", e)
                logger.warn(s"$entry")
        }
    }

    private def continuouslyImport(conf: SipgateImportConfig, wsClient: HttpClient): Future[Any] = {
        wsClient.sendAsync(buildRequest(conf), BodyHandlers.ofString()).toScala
            .flatMap { r =>
                if (r.statusCode() == 200) Future { parse(r.body()).flatMap(_.as[Contacts]).getOrElse(null) }
                else Future.failed(Exception(s"HTTP request failed: ${r.statusCode()}"))
            }
            .map { contacts =>
                connectAndBindToLdap(conf).map { ldapConn =>
                    try {
                        importContacts(conf, contacts.items, ldapConn)
                    } finally {
                        ldapConn.close()
                    }
                }
                contacts
            }
    }

    def main(args: Array[String]): Unit = {
        if (args.length < 1) {
            System.err.println("Missing config file!")
            System.err.println("Usage: <config file>")
            System.exit(1)
        }

        val configPath = Paths.get(args(0))
        if (!Files.isReadable(configPath)) {
            System.err.println(s"Config file $configPath is not readable!")
            System.exit(2)
        }

        val config = readConfig(configPath)

        val wsClient = HttpClient.newHttpClient()

        while (true) {
            logger.info("Synchronization started!")
            Await.ready(continuouslyImport(config, wsClient), Duration.Inf)
            logger.info(s"Synchronization complete, waiting for ${config.resyncDelay.toMinutes} minutes...")
            Thread.sleep(config.resyncDelay.toMillis)
        }

        wsClient.close()
    }
}
