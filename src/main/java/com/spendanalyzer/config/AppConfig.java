package com.spendanalyzer.config;

import java.time.Clock;
import java.time.ZoneId;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AppConfig {

	/** The one clock of the application. Tests replace it to control time. */
	@Bean
	public Clock clock() {
		return Clock.systemUTC();
	}

	/** The zone used for "today", "this month" and every other calendar calculation. */
	@Bean
	public ZoneId appZone(@Value("${app.zone:Asia/Kolkata}") String zone) {
		return ZoneId.of(zone);
	}

}
