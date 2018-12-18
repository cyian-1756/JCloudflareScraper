package de.jcloudflarescraper;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.http.HttpEntity;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.ParseException;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.CookieStore;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.cookie.Cookie;
import org.apache.http.impl.client.BasicCookieStore;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Scriptable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Scraper to bypass Cloudflare DDOS/anti-bot landing page.
 * @author Maximilian Pfister
 */
public class CloudflareScraper {

	/* constants */
	
	private static final Logger LOGGER = LoggerFactory.getLogger(CloudflareScraper.class);

	private final Pattern OPERATION_PATTERN = Pattern.compile("setTimeout\\(function\\(\\)\\{\\s+(var s,t,o,p,b,r,e,a,k,i,n,g,f.+?\\r?\\n[\\s\\S]+?a\\.value =.+?)\\r?\\n");
	private final Pattern PASS_PATTERN = Pattern.compile("name=\"pass\" value=\"(.+?)\"");
	private final Pattern CHALLENGE_PATTERN = Pattern.compile("name=\"jschl_vc\" value=\"(\\w+)\"");
	//TODO private final Pattern FORM_ACTION = Pattern.compile("<form id=\"challenge-form\" action=\"(.+?)\"");
	
	public static final String[] USER_AGENTS = {
			"Mozilla/5.0 (Macintosh; Intel Mac OS X 10.12; rv:56.0) Gecko/20100101 Firefox/56.0",
			"Mozilla/5.0 (Macintosh; Intel Mac OS X 10_10_5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/50.0.2661.102 Safari/537.36",
			"Mozilla/5.0 (Windows NT 6.1) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/41.0.2228.0 Safari/537.36",
			"Mozilla/5.0 (Windows NT 6.1; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/52.0.2743.116 Safari/537.36",
			"Mozilla/5.0 (Windows NT 6.1; WOW64; rv:46.0) Gecko/20100101 Firefox/46.0",
			"Mozilla/5.0 (X11; Ubuntu; Linux x86_64; rv:41.0) Gecko/20100101 Firefox/41.0" };

	/* variables */

	private HttpClient httpClient;
	private CookieStore cookieStore;

	/* constructors */

	public CloudflareScraper() {
		cookieStore = new BasicCookieStore();
		httpClient = HttpClients.custom().setUserAgent(USER_AGENTS[0]).setDefaultCookieStore(cookieStore).build();
	}

	/* public methods */
	
	public Cookie getCookie() {
		return cookieStore.getCookies().stream().filter( cookie -> cookie.getName().equals("__cfduid")).findFirst().get();
	}

	public boolean checkAndSolve(String domain)
			throws ClientProtocolException, URISyntaxException, InterruptedException, IOException {
		URIBuilder builder = new URIBuilder();
		builder.setScheme("https").setHost(domain);
		HttpGet httpGet = new HttpGet(builder.build());
		HttpResponse response = httpClient.execute(httpGet);
		HttpEntity entity = response.getEntity();

		String responseContent = "";
		try {
			responseContent = EntityUtils.toString(entity, StandardCharsets.UTF_8.name());
		} catch (ParseException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		// Check if Cloudflare anti-bot is on
		if (response.getStatusLine().getStatusCode() == 503 
				&& responseContent.contains("jschl_vc")
				&& responseContent.contains("jschl_answer")) {
			LOGGER.info("Cloudflare DDOS protection detected. Attempting to bypass...");
			if (!cloudFlareSolve(responseContent, domain)) {
				// Quiz implementation changed.
				// TODO eg. throw exception
				LOGGER.info("Cloudflare Quiz implementation changed. Aborting...");
				return false;
			}
			LOGGER.info("Successfully bypassed Cloudflare DDOS protection.");
			return true;
		}
		return false;
	}
	
	/* private methods */

	/**
	 * Solves the Cloudflare challange and sends it as response.
	 * @param responseString
	 * @return
	 * @throws URISyntaxException 
	 * @throws InterruptedException 
	 * @throws IOException 
	 * @throws ClientProtocolException 
	 */
	private boolean cloudFlareSolve(String responseString, String domain)
			throws URISyntaxException, ClientProtocolException, IOException {
		// initialize Rhino
		Context rhino = Context.enter();
		rhino.setOptimizationLevel(-1);

		// CF waits for response after some delay
		try {
			Thread.sleep(5000);
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		// pull out the arithmetic
		Matcher operationSearch = OPERATION_PATTERN.matcher(responseString);
		Matcher challengeSearch = CHALLENGE_PATTERN.matcher(responseString);
		Matcher passSearch = PASS_PATTERN.matcher(responseString);
		// Matcher formAction = FORM_ACTION.matcher(responseString);
		if (!operationSearch.find() || !passSearch.find() || !challengeSearch.find()) {
			return false;
		}

		String rawOperation = operationSearch.group(1); // operation
		String challengePass = passSearch.group(1); // key
		String challenge = challengeSearch.group(1); // hash

		// Cut out the assignment of the variable
		String operation = rawOperation.replaceAll("a\\.value = (parseInt\\(.+?\\)).+", "$1")
				.replaceAll("\\s{3,}[a-z](?: = |\\.).+", "");

		// These characters are not currently used in Cloudflare's arithmetic snippet
		String js = operation.replace("\n", "");

		// rhino.setOptimizationLevel(-1); // without this line rhino will not run under Android
		Scriptable scope = rhino.initStandardObjects(); // initialize the execution space

		// either do or die trying
		int result = ((Double) rhino.evaluateString(scope, js, "CloudFlare JS Challenge", 1, null)).intValue();
		String answer = String.valueOf(result + domain.length()); // answer to the javascript challenge

		String answerPath = domain + "/cdn-cgi/l/chk_jschl";

		URIBuilder builder = new URIBuilder();
		builder.setScheme("http").setHost(answerPath).setParameter("jschl_vc", challenge).setParameter("pass", challengePass)
				.setParameter("jschl_answer", answer);
		
		HttpGet request = new HttpGet(builder.build());

		request.setHeader(HttpHeaders.REFERER, "http://" + domain + "/");

		// Send answer request
		HttpResponse response = httpClient.execute(request);
		if (response.getStatusLine().getStatusCode() == HttpStatus.SC_OK) {
			// Do something with content if needed
			// String responseContent = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8.name());
			Context.exit(); // turn off Rhino
			return true;
		}

		Context.exit(); // turn off Rhino
		return false;
	}

}
