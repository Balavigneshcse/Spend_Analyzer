package com.spendanalyzer.ledger;

import com.spendanalyzer.security.AuthUser;

import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /api/export}: the user's own data as a file. A plain GET, it changes nothing. */
@RestController
public class ExportController {

	private final ExportService exports;

	private final EntryRequestParser parser;

	public ExportController(ExportService exports, EntryRequestParser parser) {
		this.exports = exports;
		this.parser = parser;
	}

	@GetMapping("/api/export")
	public ResponseEntity<ExportService.Export> export(@AuthenticationPrincipal AuthUser user) {
		String fileName = "spend-analyzer-export-" + parser.today() + ".json";
		return ResponseEntity.ok()
			// "attachment" makes the browser save the file instead of showing it; never cached anywhere.
			.header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(fileName).build().toString())
			.cacheControl(CacheControl.noStore())
			.contentType(MediaType.APPLICATION_JSON)
			.body(exports.export(user.id()));
	}

}
