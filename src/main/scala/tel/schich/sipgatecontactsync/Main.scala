package tel.schich.sipgatecontactsync

import java.nio.file.{Files, Path, Paths}

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import com.typesafe.scalalogging.StrictLogging
import org.apache.directory.api.ldap.model.entry._
import org.apache.directory.api.ldap.model.exception.{LdapException, LdapOperationException}
import org.apache.directory.api.ldap.model.message.SearchScope
import org.apache.directory.api.ldap.model.name.Dn
import org.apache.directory.ldap.client.api.{LdapConnectionConfig, LdapNetworkConnection}
import play.api.libs.json.{JsValue, Json}
import play.api.libs.ws.ahc.StandaloneAhcWSClient
import play.api.libs.ws.{StandaloneWSClient, StandaloneWSRequest}
import play.api.libs.ws.JsonBodyReadables._

import scala.collection.mutable
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}
import scala.concurrent.ExecutionContext.Implicits._
import scala.io.Source
import scala.util.{Either, Left, Right}
import scala.jdk.CollectionConverters._

object Main extends StrictLogging {

    val SipgateContactsEndpoint = "https://api.sipgate.com/v2/contacts"
    //val SipgateContactsEndpoint = "http://localhost:4444/v2/contacts"

    val ObjectClasses: Set[String] = Set("top", "inetOrgPerson")
    val DnAttribute: String = "ou"
    val AttributeMappings: Seq[(String, Contact => Either[String, Set[String]])] = Seq(
        ("objectClass", _ => Right(ObjectClasses)),
        (DnAttribute, c => Left(c.id)),
        ("cn", c => Left(c.trimmedName)),
        ("displayName", c => Left(c.trimmedName)),
        ("sn", c => Left(c.surname)),
        ("telephoneNumber", c => Right(c.numbers.map(_.number.toString).toSet)),
        ("o", c => Right(c.organization.flatMap(_.headOption).toSet)),
    )
    val AttributeNames: Set[String] = AttributeMappings.map(_._1.toLowerCase).toSet

    def connectAndBindToLdap(conf: SipgateImportConfig): Future[LdapNetworkConnection] = Future {
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

    def readConfig(path: Path): SipgateImportConfig = {
        val content = Source.fromFile(path.toFile).mkString
        Json.parse(content).as[SipgateImportConfig]
    }

    def buildRequest(client: StandaloneWSClient, conf: SipgateImportConfig, limit: Int = 5000, offset: Int = 0): StandaloneWSRequest = {
        val headers = Seq(
            "Accept" -> "application/json",
            "Authorization" -> s"${conf.sipgateAuth}",
        )
        client
            .url(SipgateContactsEndpoint)
            .withQueryStringParameters("limit" -> limit.toString, "offset" -> offset.toString)
            .withHttpHeaders(headers: _*)
    }

    def importContacts(conf: SipgateImportConfig, contacts: Seq[Contact], ldapConn: LdapNetworkConnection): Unit = {

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
                            else logger.info(s"Contact ${contact.id} (${contact.name}) is already up to date!")
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
            println(s"Deleted obsolete entry $id.")
        }

    }

    def updateEntry(ldapConn: LdapNetworkConnection, entry: Entry, contact: Contact): Boolean = {

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
            println(entry)
            modifications.foreach(println)
            ldapConn.modify(entry.getDn, modifications: _*)
            true
        } else false
    }

    def compareAndModify(entry: Entry, attributeName: String, contactValue: String): Option[Modification] = {
        val attribute = entry.get(attributeName)
        if (attribute != null) {
            val ldapValue = attribute.get().toString
            if (ldapValue != contactValue) Some(new DefaultModification(ModificationOperation.REPLACE_ATTRIBUTE, attributeName, contactValue))
            else None
        } else {
            Some(new DefaultModification(ModificationOperation.ADD_ATTRIBUTE, attributeName, contactValue))
        }
    }

    def compareAndModify(entry: Entry, attributeName: String, contactValues: Set[String]): Seq[Modification] = {
        val attribute = entry.get(attributeName)
        if (attribute != null) {
            val ldapValues = attribute.iterator().asScala.map(_.toString).toSet
            val obsoleteValues = ldapValues.filterNot(contactValues.contains).toSeq
            val newValues = contactValues.filterNot(ldapValues.contains).toSeq

            val removal = if (obsoleteValues.nonEmpty) {
                Seq(new DefaultModification(ModificationOperation.REMOVE_ATTRIBUTE, attributeName, obsoleteValues: _*))
            } else Nil

            val addition = if (newValues.nonEmpty) {
                Seq(new DefaultModification(ModificationOperation.ADD_ATTRIBUTE, attributeName, newValues: _*))
            } else Nil

            removal ++ addition
        } else {
            if (contactValues.nonEmpty) {
                Seq(new DefaultModification(ModificationOperation.ADD_ATTRIBUTE, attributeName, contactValues.toSeq: _*))
            } else Nil
        }
    }

    def createNewEntry(ldapConn: LdapNetworkConnection, baseDn: Dn, contact: Contact): Unit = {

        val entry = new DefaultEntry()
        entry.setDn(baseDn.add(s"$DnAttribute=${contact.id}"))
        for ((attributeName, mapping) <- AttributeMappings) {
            mapping(contact) match {
                case Left(value) =>
                    entry.add(attributeName, value)
                case Right(values) =>
                    entry.add(attributeName, values.toSeq: _*)
            }
        }

        try {
            ldapConn.add(entry)
        } catch {
            case e: LdapOperationException =>
                logger.warn("Failed to add entry!", e)
        }
    }

    def continuouslyImport(conf: SipgateImportConfig, wsClient: StandaloneWSClient): Future[Unit] = {
        val futureContacts = buildRequest(wsClient, conf)
            .get()
            .map { r =>
                if (r.status == 200) Right(r.body[JsValue].as[Contacts])
                else Left(s"HTTP request failed: ${r.statusText}")
            }


        futureContacts.flatMap {
            case Right(contacts) =>
                connectAndBindToLdap(conf).map { ldapConn =>
                    try {
                        importContacts(conf, contacts.items, ldapConn)
                    } finally {
                        ldapConn.close()
                    }
                }
            case Left(error) =>
                logger.warn(s"Error: $error")
                Future.successful(())
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

        val system = ActorSystem()
        val wsClient = StandaloneAhcWSClient()(ActorMaterializer()(system))

        while (true) {
            logger.info("Synchronization started!")
            Await.ready(continuouslyImport(config, wsClient), Duration.Inf)
            logger.info(s"Synchronization complete, waiting for ${config.resyncDelay.toMinutes} minutes...")
            Thread.sleep(config.resyncDelay.toMillis)
        }

        wsClient.close()
        system.terminate()
    }
}
