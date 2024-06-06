package tel.schich.sipgatecontactsync

import java.time.Duration

import com.google.i18n.phonenumbers.{NumberParseException, PhoneNumberUtil}
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberFormat
import play.api.libs.json.{Format, JsError, JsResult, JsString, JsSuccess, JsValue, Json}

case class SipgateImportConfig(resyncDelay: Duration,
                               sipgateAuth: String,
                               ldapHost: String,
                               ldapPort: Int,
                               ldapBindUser: String,
                               ldapBindPassword: String,
                               ldapBaseDn: String)

object SipgateImportConfig {
    implicit val format: Format[SipgateImportConfig] = Json.format
}

case class Contacts(items: Seq[Contact])
case class Contact(id: String, name: String, picture: Option[String], emails: Seq[ContactEmail], numbers: Seq[ContactNumber], addresses: Seq[ContactAddress], organization: Seq[Seq[String]], scope: String) {
    val trimmedName: String = name.trim
    val hasName: Boolean = this.trimmedName.nonEmpty
    val surname: String = {
        val lastSpace = this.trimmedName.lastIndexOf(' ')
        if (lastSpace == -1) this.trimmedName
        else this.trimmedName.substring(lastSpace + 1)
    }
}
case class ContactEmail(email: String, `type`: Seq[String])
case class ContactNumber(number: ContactNumber.InternationalNumber, `type`: Seq[String])
case class ContactAddress(poBox: Option[String], extendedAddress: Option[String], streetAddress: Option[String], locality: Option[String], region: Option[String], postalCode: Option[String], country: Option[String])

object Contacts {
    implicit val format: Format[Contacts] = Json.format
}

object Contact {
    implicit val format: Format[Contact] = Json.format
}

object ContactEmail {
    implicit val format: Format[ContactEmail] = Json.format
}

object ContactNumber {
    private type InternationalNumber = String

    implicit val format: Format[ContactNumber] = Json.format

    implicit object InternationalNumberFormat extends Format[ContactNumber.InternationalNumber] {
        override def writes(o: ContactNumber.InternationalNumber): JsValue = JsString(o)

        override def reads(json: JsValue): JsResult[ContactNumber.InternationalNumber] = {
            json match {
                case JsString(s) =>
                    val numUtil = PhoneNumberUtil.getInstance()
                    try {
                        val number = numUtil.parse(s, "DE")
                        JsSuccess(numUtil.format(number, PhoneNumberFormat.INTERNATIONAL))
                    } catch {
                        case _: NumberParseException => JsError(s"Unable to process the number: $s")
                    }
                case _ => JsError("String expected")
            }
        }
    }
}

object ContactAddress {
    implicit val format: Format[ContactAddress] = Json.format
}