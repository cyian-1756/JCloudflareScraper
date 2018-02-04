package de.jcloudflarescraper;

import java.io.IOException;
import java.net.URISyntaxException;

import org.apache.http.client.ClientProtocolException;
import org.apache.http.cookie.Cookie;

public class Example {

	public static void main(String[] args) throws ClientProtocolException, URISyntaxException, InterruptedException, IOException {
		CloudflareScraper scraper = new CloudflareScraper();
		
		if(scraper.checkAndSolve("bittrex.com")) {
			Cookie cookie = scraper.getCookie();
			System.out.println(cookie.toString());
		} else {
			System.out.println("No Cloudflare landing page detected");
		}

	}

}
