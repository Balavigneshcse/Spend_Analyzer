package com.spendanalyzer.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.ServletRequest;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** The same-origin filter on its own: which requests it checks and how it caps the body. */
class SameOriginApiFilterTest {

	private static final int CAP = SameOriginApiFilter.MAX_BODY_BYTES;

	private final SameOriginApiFilter filter = new SameOriginApiFilter("https://spend.example");

	private final MockHttpServletResponse response = new MockHttpServletResponse();

	private final MockFilterChain chain = new MockFilterChain();

	private static MockHttpServletRequest jsonPost(String uri, int bodyBytes) {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
		request.setContentType("application/json");
		request.setContent(body(bodyBytes));
		return request;
	}

	/** What the server hands over for "Transfer-Encoding: chunked": a body, but no length. */
	private static MockHttpServletRequest chunkedJsonPost(int bodyBytes) {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/register") {
			@Override
			public int getContentLength() {
				return -1;
			}

			@Override
			public long getContentLengthLong() {
				return -1;
			}
		};
		request.addHeader("Transfer-Encoding", "chunked");
		request.setContentType("application/json");
		request.setContent(body(bodyBytes));
		return request;
	}

	private static byte[] body(int bytes) {
		return ("\"" + "x".repeat(bytes - 2) + "\"").getBytes(StandardCharsets.UTF_8);
	}

	/** Runs the filter and returns the request it passed on. */
	private HttpServletRequest passedOn(MockHttpServletRequest request) throws Exception {
		MockFilterChain passing = new MockFilterChain();
		filter.doFilter(request, response, passing);
		ServletRequest passedOn = passing.getRequest();
		assertThat(passedOn).as("the request was passed on").isNotNull();
		return (HttpServletRequest) passedOn;
	}

	@Test
	void aBodyAnnouncedAsLargerThanTheCapIsRefusedUnread() throws Exception {
		filter.doFilter(jsonPost("/api/auth/register", CAP + 1), response, chain);

		assertThat(response.getStatus()).isEqualTo(413);
		assertThat(response.getContentType()).startsWith("application/json");
		assertThat(response.getContentAsString())
			.isEqualTo("{\"error\":\"too_large\",\"message\":\"That request is too large.\"}");
		assertThat(chain.getRequest()).isNull();
	}

	@Test
	void aBodyOfExactlyTheCapIsPassedOn() throws Exception {
		MockHttpServletRequest request = jsonPost("/api/auth/register", CAP);

		assertThat(passedOn(request).getInputStream().readAllBytes()).hasSize(CAP);
		assertThat(response.getStatus()).isEqualTo(200);
	}

	@Test
	void aBodyWithoutALengthCannotBeReadBeyondTheCapAsAStream() throws Exception {
		HttpServletRequest request = passedOn(chunkedJsonPost(CAP + 1));

		assertThatThrownBy(() -> request.getInputStream().readAllBytes()).isInstanceOf(IOException.class);
		assertThat(request.getAttribute(SameOriginApiFilter.BODY_TOO_LARGE_ATTRIBUTE)).isNotNull();
		// and it stays that way, however the next read is made
		assertThatThrownBy(() -> request.getInputStream().read(new byte[64], 0, 64)).isInstanceOf(IOException.class);
		assertThatThrownBy(() -> request.getInputStream().read()).isInstanceOf(IOException.class);
	}

	@Test
	void aBodyWithoutALengthCannotBeReadBeyondTheCapByteByByte() throws Exception {
		HttpServletRequest request = passedOn(chunkedJsonPost(CAP + 1));

		for (int i = 0; i < CAP; i++) {
			assertThat(request.getInputStream().read()).isNotNegative();
		}
		assertThat(request.getAttribute(SameOriginApiFilter.BODY_TOO_LARGE_ATTRIBUTE)).isNull();
		assertThatThrownBy(() -> request.getInputStream().read()).isInstanceOf(IOException.class);
		assertThat(request.getAttribute(SameOriginApiFilter.BODY_TOO_LARGE_ATTRIBUTE)).isNotNull();
	}

	@Test
	void aBodyWithoutALengthCannotBeReadBeyondTheCapAsAReader() throws Exception {
		HttpServletRequest request = passedOn(chunkedJsonPost(CAP + 1));

		assertThatThrownBy(() -> request.getReader().transferTo(Writer.nullWriter())).isInstanceOf(IOException.class);
		assertThat(request.getAttribute(SameOriginApiFilter.BODY_TOO_LARGE_ATTRIBUTE)).isNotNull();
	}

	@Test
	void aBodyWithoutALengthUpToTheCapIsReadInFull() throws Exception {
		HttpServletRequest asStream = passedOn(chunkedJsonPost(CAP));
		assertThat(asStream.getInputStream().readAllBytes()).isEqualTo(body(CAP));
		assertThat(asStream.getAttribute(SameOriginApiFilter.BODY_TOO_LARGE_ATTRIBUTE)).isNull();

		HttpServletRequest asReader = passedOn(chunkedJsonPost(CAP));
		assertThat(asReader.getReader().readLine()).isEqualTo(new String(body(CAP), StandardCharsets.UTF_8));
		assertThat(asReader.getAttribute(SameOriginApiFilter.BODY_TOO_LARGE_ATTRIBUTE)).isNull();
	}

	@Test
	void everyStateChangingRequestIsCheckedWhateverItsPathLooksLike() throws Exception {
		for (String uri : new String[] { "/api/auth/logout", "/%61pi/auth/logout", "/API/auth/logout", "/elsewhere" }) {
			MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
			request.addHeader("Origin", "https://evil.example");
			MockHttpServletResponse refused = new MockHttpServletResponse();
			MockFilterChain untouched = new MockFilterChain();

			filter.doFilter(request, refused, untouched);

			assertThat(refused.getStatus()).as(uri).isEqualTo(403);
			assertThat(refused.getContentAsString()).as(uri).contains("\"error\":\"cross_site\"");
			assertThat(untouched.getRequest()).as(uri).isNull();
		}
	}

	@Test
	void readingRequestsAreLeftAlone() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/balances");
		request.addHeader("Origin", "https://evil.example");

		filter.doFilter(request, response, chain);

		assertThat(response.getStatus()).isEqualTo(200);
		assertThat(chain.getRequest()).isSameAs(request);
	}

}
