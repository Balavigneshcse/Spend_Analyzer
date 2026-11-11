package com.spendanalyzer.ledger;

import java.io.IOException;

import com.spendanalyzer.ledger.excel.XlsxWriter;
import com.spendanalyzer.security.AuthUser;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/export} (JSON, the full backup) and {@code GET /api/export/excel}: the user's own
 * data as a file. Plain GETs, they change nothing.
 */
@RestController
public class ExportController {

	private final ExportService exports;

	private final ExcelExport excel;

	private final EntryRequestParser parser;

	public ExportController(ExportService exports, ExcelExport excel, EntryRequestParser parser) {
		this.exports = exports;
		this.excel = excel;
		this.parser = parser;
	}

	@GetMapping("/api/export")
	public ResponseEntity<ExportService.Export> export(@AuthenticationPrincipal AuthUser user) {
		String fileName = "spend-analyzer-export-" + parser.today() + ".json";
		return ResponseEntity.ok()
			// "attachment" makes the browser save the file instead of showing it; never cached anywhere.
			.header(HttpHeaders.CONTENT_DISPOSITION, attachment(fileName))
			.cacheControl(CacheControl.noStore())
			.contentType(MediaType.APPLICATION_JSON)
			.body(exports.export(user.id()));
	}

	@GetMapping("/api/export/excel")
	public void excel(@AuthenticationPrincipal AuthUser user, HttpServletResponse response) throws IOException {
		// Read everything first: until a byte is written, an error still becomes the usual JSON answer.
		ExportService.Snapshot data = exports.snapshot(user.id());
		response.setStatus(HttpServletResponse.SC_OK);
		response.setContentType(XlsxWriter.CONTENT_TYPE);
		response.setHeader(HttpHeaders.CONTENT_DISPOSITION, attachment("spend-analyzer-" + parser.today() + ".xlsx"));
		response.setHeader(HttpHeaders.CACHE_CONTROL, CacheControl.noStore().getHeaderValue());
		// Streamed: the rows go out as they are written, the file is never held whole in memory.
		excel.write(data, response.getOutputStream());
		response.flushBuffer();
	}

	private static String attachment(String fileName) {
		return ContentDisposition.attachment().filename(fileName).build().toString();
	}

}
