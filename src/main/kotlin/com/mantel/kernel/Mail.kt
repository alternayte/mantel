package com.mantel.kernel

import jakarta.mail.Authenticator
import jakarta.mail.PasswordAuthentication
import jakarta.mail.Session
import jakarta.mail.Transport
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import org.slf4j.LoggerFactory
import java.util.Properties

data class Mail(val to: String, val subject: String, val body: String)

fun interface Mailer {
    fun send(mail: Mail)
}

/**
 * No SMTP host configured, so the mail goes to the log. This is how local work reads its own magic
 * link, and how a misconfigured deployment fails loudly instead of silently dropping sign-ins.
 */
class LoggingMailer : Mailer {
    private val log = LoggerFactory.getLogger("com.mantel.mail")

    override fun send(mail: Mail) {
        log.warn("No SMTP host configured. Mail to {} not sent.\n{}\n\n{}", mail.to, mail.subject, mail.body)
    }
}

class SmtpMailer(private val config: SmtpConfig) : Mailer {
    private val session: Session =
        Session.getInstance(
            Properties().apply {
                put("mail.smtp.host", config.host)
                put("mail.smtp.port", config.port.toString())
                put("mail.smtp.auth", (config.username != null).toString())
                put("mail.smtp.starttls.enable", config.startTls.toString())
            },
            config.username?.let { user ->
                object : Authenticator() {
                    override fun getPasswordAuthentication() = PasswordAuthentication(user, config.password ?: "")
                }
            },
        )

    override fun send(mail: Mail) {
        val message =
            MimeMessage(session).apply {
                setFrom(InternetAddress(config.from))
                setRecipients(jakarta.mail.Message.RecipientType.TO, InternetAddress.parse(mail.to))
                subject = mail.subject
                setText(mail.body, "utf-8")
            }
        Transport.send(message)
    }
}

fun mailerFor(config: SmtpConfig?): Mailer = if (config == null) LoggingMailer() else SmtpMailer(config)
