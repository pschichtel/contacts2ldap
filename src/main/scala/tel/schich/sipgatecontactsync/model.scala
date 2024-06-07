package tel.schich.sipgatecontactsync

import java.time.Duration
import com.google.i18n.phonenumbers.{NumberParseException, PhoneNumberUtil}
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberFormat
import io.circe.Decoder.Result
import io.circe.{Decoder, Encoder, HCursor}
import io.circe.derivation.{Configuration, ConfiguredCodec}

private given Configuration = Configuration.default

case class SipgateImportConfig(resyncDelay: Duration,
                               sipgateAuth: String,
                               ldapHost: String,
                               ldapPort: Int,
                               ldapBindUser: String,
                               ldapBindPassword: String,
                               ldapBaseDn: String) derives ConfiguredCodec

case class Contacts(items: Seq[Contact]) derives ConfiguredCodec
case class Contact(id: String,
                   name: String,
                   picture: Option[String],
                   emails: Seq[ContactEmail],
                   numbers: Seq[ContactNumber],
                   addresses: Seq[ContactAddress],
                   organization: Seq[Seq[String]],
                   scope: String) derives ConfiguredCodec {
    val trimmedName: String = name.trim
    val hasName: Boolean = this.trimmedName.nonEmpty
    val surname: String = {
        val lastSpace = this.trimmedName.lastIndexOf(' ')
        if (lastSpace == -1) this.trimmedName
        else this.trimmedName.substring(lastSpace + 1)
    }
}
case class ContactEmail(email: String, `type`: Seq[String]) derives ConfiguredCodec
case class ContactNumber(number: InternationalNumber, `type`: Seq[String]) derives ConfiguredCodec
case class ContactAddress(poBox: Option[String],
                          extendedAddress: Option[String],
                          streetAddress: Option[String],
                          locality: Option[String],
                          region: Option[String],
                          postalCode: Option[String],
                          country: Option[String]) derives ConfiguredCodec

opaque type InternationalNumber = String

extension (n: InternationalNumber) {
    def e164: String = n
}

object InternationalNumber {
    implicit val encoder: Encoder[InternationalNumber] = Encoder.encodeString
    implicit val decoder: Decoder[InternationalNumber] = Decoder.decodeString.emap { str =>
        val numUtil = PhoneNumberUtil.getInstance()
        try {
            val number = numUtil.parse(str, "DE")
            Right(numUtil.format(number, PhoneNumberFormat.INTERNATIONAL))
        } catch {
            case _: NumberParseException => Left(s"Unable to process the number: $str")
        }
    }
}
