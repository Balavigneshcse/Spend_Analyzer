package com.spendanalyzer.insight;

import com.spendanalyzer.common.ApiException;
import com.spendanalyzer.insight.engine.Analysis;
import com.spendanalyzer.insight.engine.PeriodCode;
import com.spendanalyzer.security.AuthUser;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** GET /api/analysis: the spending analysis of the logged-in user for one period. */
@RestController
public class AnalysisController {

	private final AnalysisService service;

	public AnalysisController(AnalysisService service) {
		this.service = service;
	}

	@GetMapping("/api/analysis")
	public Analysis analysis(@AuthenticationPrincipal AuthUser user,
			@RequestParam(name = "period", required = false) String period) {
		PeriodCode code = PeriodCode.THIS_MONTH;
		if (period != null && !period.isBlank()) {
			code = PeriodCode.parse(period)
				.orElseThrow(() -> ApiException.field("period",
						"Choose a period: THIS_WEEK, THIS_MONTH, LAST_MONTH, LAST_30_DAYS or ALL_TIME."));
		}
		return service.analyse(user.id(), code);
	}

}
