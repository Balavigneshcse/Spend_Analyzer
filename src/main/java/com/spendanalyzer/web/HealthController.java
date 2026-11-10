package com.spendanalyzer.web;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Health check for the hosting platform. Must stay cheap: no database access. */
@RestController
public class HealthController {

	@GetMapping(value = "/healthz", produces = MediaType.TEXT_PLAIN_VALUE)
	public String health() {
		return "ok";
	}

}
