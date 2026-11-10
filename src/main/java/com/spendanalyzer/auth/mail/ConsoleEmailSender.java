package com.spendanalyzer.auth.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

/**
 * LOCAL DEVELOPMENT ONLY: writes the mail, including the code, to the log instead of sending it.
 * In production this would put every code into the hosting provider's log viewer, so the bean
 * does not exist there ({@link NotProductionCondition}) and {@link ProductionGuard} refuses to
 * start with this provider.
 */
@Component
@ConditionalOnProperty(name = "app.mail.provider", havingValue = "console")
@Conditional(NotProductionCondition.class)
public class ConsoleEmailSender implements EmailSender {

	private static final Logger log = LoggerFactory.getLogger(ConsoleEmailSender.class);

	@Override
	public void send(EmailMessage message) {
		log.info("\n---- DEV MAIL (not sent) ----\nTo: {}\nSubject: {}\n\n{}-----------------------------", message.to(),
				message.subject(), message.text());
	}

}
