package com.spendanalyzer.security;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Cross-site request forgery defence for the cookie-authenticated API.
 * <p>
 * Every state-changing request must (1) come from this site, judged by the browser's
 * {@code Sec-Fetch-Site} and {@code Origin} headers, and (2) carry a JSON body if it has a body at
 * all (an HTML form cannot send {@code application/json}). Together with the SameSite=Strict
 * session cookie this replaces a CSRF token. There is deliberately no CORS configuration.
 * <p>
 * The same requests are the only ones with a body, so the size of a body is capped here too: no
 * request of this API comes near {@value #MAX_BODY_BYTES} bytes, and without a cap a few huge JSON
 * bodies would fill the small heap.
 */
public class SameOriginApiFilter extends OncePerRequestFilter {

	/** The largest request body that is read. */
	public static final int MAX_BODY_BYTES = 16 * 1024;

	/**
	 * Set on the request when a body of unknown length turned out to be over the cap while it was
	 * being read, so that the error handler can answer 413 rather than "could not be read".
	 */
	public static final String BODY_TOO_LARGE_ATTRIBUTE = SameOriginApiFilter.class.getName() + ".bodyTooLarge";

	private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

	private final String configuredOrigin;

	public SameOriginApiFilter(String configuredOrigin) {
		this.configuredOrigin = normalise(configuredOrigin);
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		// Only the method decides: this site has no state-changing request outside the API, and a
		// test of the raw path would let a percent-encoded one (/%61pi/...) slip past.
		return SAFE_METHODS.contains(request.getMethod());
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String fetchSite = request.getHeader("Sec-Fetch-Site");
		if (fetchSite != null && !"same-origin".equalsIgnoreCase(fetchSite)) {
			reject(response, HttpServletResponse.SC_FORBIDDEN, "cross_site", "Cross-site requests are not allowed.");
			return;
		}
		String origin = request.getHeader("Origin");
		if (origin != null && !isOwnOrigin(origin, request)) {
			reject(response, HttpServletResponse.SC_FORBIDDEN, "cross_site", "Cross-site requests are not allowed.");
			return;
		}
		long length = request.getContentLengthLong();
		if (length > MAX_BODY_BYTES) {
			reject(response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, "too_large",
					"That request is too large.");
			return;
		}
		if (hasBody(request)) {
			String contentType = request.getContentType();
			if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("application/json")) {
				reject(response, HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE, "json_required",
						"Requests must be sent as JSON.");
				return;
			}
		}
		// No Content-Length (a chunked body): the size is only known while reading, so count there.
		chain.doFilter(length < 0 ? new CappedRequest(request) : request, response);
	}

	private boolean isOwnOrigin(String origin, HttpServletRequest request) {
		String candidate = normalise(origin);
		if (candidate == null) {
			return false;
		}
		if (configuredOrigin != null && configuredOrigin.equals(candidate)) {
			return true;
		}
		// Same host and port as the request itself (the Host header cannot be forged by another site).
		String host = request.getHeader("Host");
		if (host == null) {
			return false;
		}
		try {
			String authority = URI.create(candidate).getAuthority();
			return authority != null && authority.equalsIgnoreCase(host.trim());
		}
		catch (IllegalArgumentException ex) {
			return false;
		}
	}

	private static boolean hasBody(HttpServletRequest request) {
		return request.getContentLengthLong() > 0 || request.getHeader("Transfer-Encoding") != null;
	}

	private static String normalise(String origin) {
		if (origin == null || origin.isBlank()) {
			return null;
		}
		String value = origin.trim().toLowerCase(Locale.ROOT);
		while (value.endsWith("/")) {
			value = value.substring(0, value.length() - 1);
		}
		return value;
	}

	private static void reject(HttpServletResponse response, int status, String code, String message)
			throws IOException {
		response.setStatus(status);
		response.setContentType("application/json");
		response.setCharacterEncoding("UTF-8");
		response.getWriter().write("{\"error\":\"" + code + "\",\"message\":\"" + message + "\"}");
	}

	/** A request whose body cannot be read beyond {@link #MAX_BODY_BYTES}, by stream or by reader. */
	private static final class CappedRequest extends HttpServletRequestWrapper {

		private ServletInputStream stream;

		private BufferedReader reader;

		CappedRequest(HttpServletRequest request) {
			super(request);
		}

		@Override
		public ServletInputStream getInputStream() throws IOException {
			if (stream == null) {
				stream = new CappedInputStream(super.getInputStream(), this);
			}
			return stream;
		}

		@Override
		public BufferedReader getReader() throws IOException {
			if (reader == null) {
				String encoding = getCharacterEncoding();
				Charset charset;
				try {
					charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
				}
				catch (IllegalArgumentException ex) {
					throw new UnsupportedEncodingException(encoding);
				}
				// over the capped stream, so that the bytes are counted on this path as well
				reader = new BufferedReader(new InputStreamReader(getInputStream(), charset));
			}
			return reader;
		}

	}

	private static final class CappedInputStream extends ServletInputStream {

		private final ServletInputStream in;

		private final HttpServletRequest request;

		private int remaining = MAX_BODY_BYTES;

		CappedInputStream(ServletInputStream in, HttpServletRequest request) {
			this.in = in;
			this.request = request;
		}

		@Override
		public int read() throws IOException {
			refuseIfOver();
			int value = in.read();
			if (value >= 0) {
				remaining--;
				refuseIfOver();
			}
			return value;
		}

		@Override
		public int read(byte[] buffer, int offset, int length) throws IOException {
			refuseIfOver();
			// never takes in more than one byte beyond the cap
			int read = in.read(buffer, offset, Math.min(length, remaining + 1));
			if (read > 0) {
				remaining -= read;
				refuseIfOver();
			}
			return read;
		}

		/** Fails once the cap is passed, and on every read after that. */
		private void refuseIfOver() throws IOException {
			if (remaining < 0) {
				request.setAttribute(BODY_TOO_LARGE_ATTRIBUTE, Boolean.TRUE);
				throw new IOException("The request body is larger than " + MAX_BODY_BYTES + " bytes");
			}
		}

		@Override
		public int available() throws IOException {
			return in.available();
		}

		@Override
		public void close() throws IOException {
			in.close();
		}

		@Override
		public boolean isFinished() {
			return in.isFinished();
		}

		@Override
		public boolean isReady() {
			return in.isReady();
		}

		@Override
		public void setReadListener(ReadListener listener) {
			in.setReadListener(listener);
		}

	}

}
