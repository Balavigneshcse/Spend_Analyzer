package com.spendanalyzer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import com.spendanalyzer.config.DatabaseUrl;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class SpendAnalyzerApplication {

	public static void main(String[] args) {
		useSafeSocketFolderOnWindows();
		// Hosting providers hand out "postgresql://user:pass@host/db" which the JDBC driver cannot read.
		DatabaseUrl.fromEnvironment().ifPresent(DatabaseUrl::applyAsSystemProperties);
		SpringApplication.run(SpendAnalyzerApplication.class, args);
	}

	/**
	 * On Windows the JDK keeps a small socket file in the Temp folder for its network selector. On
	 * some PCs that folder refuses socket files and the web server then fails to start with "Unable
	 * to establish loopback connection". A folder in the user's home always works, so use that
	 * unless the user chose one. Must run before any network class is loaded. Linux is untouched.
	 */
	static void useSafeSocketFolderOnWindows() {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		if (!os.startsWith("windows") || System.getProperty("jdk.net.unixdomain.tmpdir") != null) {
			return;
		}
		try {
			Path folder = Path.of(System.getProperty("user.home"), ".spend-analyzer");
			Files.createDirectories(folder);
			System.setProperty("jdk.net.unixdomain.tmpdir", folder.toString());
		}
		catch (Exception ex) {
			// keep the JDK default; start-up will report the real problem if there is one
		}
	}

}
