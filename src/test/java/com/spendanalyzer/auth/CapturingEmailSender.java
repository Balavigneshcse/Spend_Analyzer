package com.spendanalyzer.auth;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.spendanalyzer.auth.mail.EmailMessage;
import com.spendanalyzer.auth.mail.EmailSender;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The mail sender of the test profile ({@code app.mail.provider=capture}): keeps every mail in
 * memory so a test can read the code that "was mailed". Mails arrive on a background thread:
 * wait with {@code MailDispatcher.awaitIdle} before reading.
 */
@Component
@ConditionalOnProperty(name = "app.mail.provider", havingValue = "capture")
public class CapturingEmailSender implements EmailSender {

	private static final Pattern CODE = Pattern.compile("^([0-9]{6}) is your Spend Analyzer code$");

	private final Map<String, List<EmailMessage>> byRecipient = new ConcurrentHashMap<>();

	@Override
	public void send(EmailMessage message) {
		byRecipient.computeIfAbsent(message.to(), to -> new CopyOnWriteArrayList<>()).add(message);
	}

	/** Every mail sent to this address, oldest first. */
	public List<EmailMessage> mailsTo(String email) {
		return List.copyOf(byRecipient.getOrDefault(email, List.of()));
	}

	/** Every code mailed to this address, oldest first. */
	public List<String> codesTo(String email) {
		List<String> codes = new ArrayList<>();
		for (EmailMessage message : mailsTo(email)) {
			Matcher matcher = CODE.matcher(message.subject());
			if (matcher.matches()) {
				codes.add(matcher.group(1));
			}
		}
		return codes;
	}

	/** The newest code mailed to this address. */
	public Optional<String> lastCode(String email) {
		List<String> codes = codesTo(email);
		return codes.isEmpty() ? Optional.empty() : Optional.of(codes.get(codes.size() - 1));
	}

}
