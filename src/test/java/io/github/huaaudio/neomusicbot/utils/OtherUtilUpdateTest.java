package io.github.huaaudio.neomusicbot.utils;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import net.dv8tion.jda.api.entities.Activity;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.json.JSONArray;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OtherUtilUpdateTest
{
    @Test
    void prereleaseUsersSeeNewBetasButStableUsersDoNot()
    {
        JSONArray releases = new JSONArray("""
            [
              {"tag_name":"v0.6.0-beta.2","prerelease":true,"draft":false},
              {"tag_name":"v0.5.0","prerelease":false,"draft":false},
              {"tag_name":"v0.7.0","prerelease":false,"draft":true},
              {"tag_name":"v0.9.0-rc..1","prerelease":true,"draft":false},
              {"tag_name":"v0.6.0-beta.1","prerelease":true,"draft":false},
              {"tag_name":"v0.8.0-rc.1","prerelease":false,"draft":false}
            ]
            """);
        assertEquals("v0.8.0-rc.1", OtherUtil.newestPublishedRelease(releases, true));
        assertEquals("v0.5.0", OtherUtil.newestPublishedRelease(releases, false));
    }

    @Test
    void updateRequestUsesTheAppropriateReleaseEndpoint()
    {
        AtomicReference<String> requested = new AtomicReference<>();
        OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> {
            requested.set(chain.request().url().toString());
            boolean beta = chain.request().url().queryParameter("per_page") != null;
            String release = "{\"tag_name\":\"0.5.0\",\"draft\":false,\"prerelease\":false}";
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK")
                    .body(ResponseBody.create(beta ? "[" + release + "]" : release,
                            MediaType.get("application/json"))).build();
        }).build();
        assertEquals("0.5.0", OtherUtil.getLatestVersion("0.4.5", client));
        assertTrue(requested.get().endsWith("/releases/latest"));
        assertEquals("0.5.0", OtherUtil.getLatestVersion("0.5.0-beta.1", client));
        assertTrue(requested.get().endsWith("/releases?per_page=100"));
    }

    @Test
    void failedOrMalformedResponsesAreNotUpdateCandidates()
    {
        for(int status : new int[]{200, 404, 429})
        {
            OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain ->
                    new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                            .code(status).message("test response")
                            .body(ResponseBody.create("not json", MediaType.get("text/plain"))).build()).build();
            assertNull(OtherUtil.getLatestVersion("0.5.0-beta.1", client));
        }
    }

    @Test
    void activityKeywordsAreIndependentOfSystemLocale()
    {
        Locale original = Locale.getDefault();
        try
        {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            Activity activity = OtherUtil.parseGame("  LISTENING TO Music  ");
            assertEquals(Activity.ActivityType.LISTENING, activity.getType());
            assertEquals("Music", activity.getName());
        }
        finally
        {
            Locale.setDefault(original);
        }
    }
}
