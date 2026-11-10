package com.spendanalyzer;

import com.spendanalyzer.config.DatabaseUrl;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class SpendAnalyzerApplication {

	public static void main(String[] args) {
		// Hosting providers hand out "postgresql://user:pass@host/db" which the JDBC driver cannot read.
		DatabaseUrl.fromEnvironment().ifPresent(DatabaseUrl::applyAsSystemProperties);
		SpringApplication.run(SpendAnalyzerApplication.class, args);
	}

}
