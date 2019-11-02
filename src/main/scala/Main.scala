import java.nio.file.{Files, Path, Paths}

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import org.apache.directory.api.ldap.model.entry.{DefaultEntry, Entry}
import org.apache.directory.api.ldap.model.exception.{LdapException, LdapOperationException}
import org.apache.directory.api.ldap.model.message.SearchScope
import org.apache.directory.api.ldap.model.name.Dn
import org.apache.directory.ldap.client.api.{LdapConnectionConfig, LdapNetworkConnection}
import play.api.libs.json.{JsValue, Json}
import play.api.libs.ws.JsonBodyReadables._
import play.api.libs.ws.ahc.StandaloneAhcWSClient
import play.api.libs.ws.{StandaloneWSClient, StandaloneWSRequest}

import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits._
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}
import scala.io.Source
import scala.jdk.CollectionConverters._

object Main {

    val SipgateContactsEndpoint = "https://api.sipgate.com/v2/contacts"
    //val SipgateContactsEndpoint = "http://localhost:4444/v2/contacts"

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
                println(s"Bind failed: ${e.getLocalizedMessage}")
                e.printStackTrace(System.err)
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
            "accept" -> "application/json",
            "authorization" -> s"Bearer ${conf.sipgateBearerToken}",
        )
        client
            .url(SipgateContactsEndpoint)
            .withQueryStringParameters("limit" -> limit.toString, "offset" -> offset.toString)
            .withHttpHeaders(headers: _*)
    }

    def importContacts(conf: SipgateImportConfig, contacts: Seq[Contact], ldapConn: LdapNetworkConnection): Unit = {

        val baseDn = new Dn(conf.ldapBaseDn)

        val lookup = mutable.Map[String, Entry]()
        val attributes = Seq("ou", "cn", "telephoneNumber")
        val cursor = ldapConn.search(baseDn, "(ou=*)", SearchScope.ONELEVEL, attributes: _*)
        while (cursor.next()) {
            val e = cursor.get()
            val id = e.get("ou").get().toString
            lookup += id -> e
        }

        for (contact <- contacts) {
            val name = contact.name.trim
            if (name.nonEmpty) {

                lookup.get(contact.id) match {
                    case Some(existing) if contactsDiffer(contact, existing) =>
                        ldapConn.delete(existing.getDn)
                        println(s"Updating contact ${contact.id} (${contact.name})")
                        createNewEntry(ldapConn, baseDn, contact, name)
                    case Some(_) =>
                        println(s"Contact ${contact.id} (${contact.name}) was already up to date!")
                    case None =>
                        println(s"Creating new contact ${contact.id} (${contact.name}).")
                        createNewEntry(ldapConn, baseDn, contact, name)

                }
            }
        }
    }

    def contactsDiffer(contact: Contact, ldapEntry: Entry): Boolean = {
        val name = ldapEntry.get("cn").get().toString
        val numbers = Option(ldapEntry.get("telephoneNumber")).map(_.iterator().asScala.map(_.toString).toSet).getOrElse(Set.empty)
        !contact.name.trim.equals(name) || !contact.numbers.map(_.number.toString).toSet.equals(numbers)
    }

    def createNewEntry(ldapConn: LdapNetworkConnection, baseDn: Dn, contact: Contact, name: String): Unit = {
        val lastSpace = name.lastIndexOf(' ')
        val surname = if (lastSpace == -1) name else name.substring(lastSpace + 1)

        val entry = new DefaultEntry()
        entry.setDn(baseDn.add(s"ou=${contact.id}"))
        entry.add("objectClass", "top", "inetOrgPerson")
        entry.add("ou", contact.id)
        entry.add("cn", name)
        entry.add("displayName", name)
        entry.add("sn", surname)

        for (number <- contact.numbers) {
            entry.add("telephoneNumber", number.number)
        }

        try {
            ldapConn.add(entry)
        } catch {
            case e: LdapOperationException =>
                System.err.println("Failed to add entry!")
                e.printStackTrace(System.err)
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

        println()

        val system = ActorSystem()
        system.registerOnTermination {
            System.exit(0)
        }
        val wsClient = StandaloneAhcWSClient()(ActorMaterializer()(system))

        val futureContacts = buildRequest(wsClient, config)
            .get()
            .map { r =>
                if (r.status == 200) Right(r.body[JsValue].as[Contacts])
                else Left(s"HTTP request failed: ${r.statusText}")
            }


        val finished = futureContacts.flatMap {
            case Right(contacts) =>
                connectAndBindToLdap(config).map { ldapConn =>
                    try {
                        importContacts(config, contacts.items, ldapConn)
                    } finally {
                        ldapConn.close()
                    }
                }
            case Left(error) =>
                System.err.println(s"Error: $error")
                Future.successful(())
        }

        Await.ready(finished, Duration.Inf)
        wsClient.close()
        system.terminate()
    }
}
