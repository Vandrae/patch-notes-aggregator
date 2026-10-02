package com.vandrae.patchnotes.externalapi;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** The store-details and most-played-chart calls of {@link SteamStoreClient}. */
class SteamStoreItemsTest {

    private static final String BASE = "https://steam.test";
    private static final String KEY = "TESTKEY1234567890ABCDEF";

    private static final String ITEMS = """
            {"response":{"store_items":[
              {"item_type":0,"id":1422450,"success":1,"appid":1422450,"name":"Deadlock",
               "reviews":{"summary_filtered":{"review_count":0,"percent_positive":0}},
               "basic_info":{"short_description":"Deadlock is a multiplayer game in early development."},
               "assets":{"asset_url_format":"steam/apps/1422450/${FILENAME}?t=1790716179",
                         "small_capsule":"88b400eca/capsule_231x87.jpg","header":"aa249c5e8/header.jpg"}},
              {"id":1374490,"success":1,"appid":1374490,
               "reviews":{"summary_filtered":{"review_count":30309}},
               "basic_info":{"short_description":"On RuneScape's forgotten continent, dragons have awoken."},
               "assets":{"asset_url_format":"steam/apps/1374490/${FILENAME}?t=5","header":"hh11/header.jpg"}},
              {"id":20,"success":2},
              {"id":30,"success":1,"appid":30,
               "assets":{"asset_url_format":"//evil.test/${FILENAME}","small_capsule":"x.jpg"}},
              {"id":40,"success":1,"appid":40,
               "assets":{"asset_url_format":"steam/apps/40/${FILENAME}","small_capsule":"../../etc/passwd"}}
            ]}}
            """;

    private MockRestServiceServer server;
    private SteamStoreClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).build();
        var props = new SteamProperties(BASE, KEY, 20, Duration.ofSeconds(1), Duration.ofSeconds(1), 3,
                Duration.ofMillis(1), Duration.ofSeconds(5), 50_000);
        client = new SteamStoreClient(props, builder.build());
    }

    // -------------------------------------------------------------------- store details

    @Test
    void askForDescriptionsImagesAndReviewsOfExactlyTheRequestedGames() {
        server.expect(requestTo(startsWith(BASE + "/IStoreBrowseService/GetItems/v1/")))
                .andExpect(request -> {
                    String query = request.getURI().getQuery(); // decoded
                    assertThat(query).contains("key=" + KEY);
                    assertThat(query).contains("\"appid\":1422450").contains("\"appid\":1374490");
                    assertThat(query).contains("\"include_assets\":true").contains("\"include_basic_info\":true")
                            .contains("\"include_reviews\":true");
                })
                .andRespond(withSuccess(ITEMS, MediaType.APPLICATION_JSON));

        client.getStoreItems(java.util.List.of(1422450L, 1374490L));

        server.verify();
    }

    @Test
    void parsesDescriptionImageAndReviewCountAndSkipsGamesWithoutAStorePage() {
        server.expect(requestTo(startsWith(BASE))).andRespond(withSuccess(ITEMS, MediaType.APPLICATION_JSON));

        var items = client.getStoreItems(java.util.List.of(1422450L, 1374490L, 20L, 30L, 40L));

        assertThat(items).containsOnlyKeys(1422450L, 1374490L, 30L, 40L); // 20 had success=2: no store page

        SteamStoreItem deadlock = items.get(1422450L);
        assertThat(deadlock.shortDescription()).isEqualTo("Deadlock is a multiplayer game in early development.");
        assertThat(deadlock.reviewCount()).isZero();
        // the small capsule is preferred, and the CDN template is filled in
        assertThat(deadlock.imagePath()).isEqualTo("steam/apps/1422450/88b400eca/capsule_231x87.jpg?t=1790716179");

        SteamStoreItem dragonwilds = items.get(1374490L);
        assertThat(dragonwilds.reviewCount()).isEqualTo(30309);
        assertThat(dragonwilds.imagePath()).isEqualTo("steam/apps/1374490/hh11/header.jpg?t=5"); // no small capsule: falls back to the header
    }

    @Test
    void asksForTheStoreTagsSoGenresCanBeDerived() {
        server.expect(requestTo(startsWith(BASE)))
                .andExpect(request -> assertThat(request.getURI().getQuery()).contains("\"include_tag_count\":30").contains("\"include_ratings\":true"))
                .andRespond(withSuccess("{\"response\":{}}", MediaType.APPLICATION_JSON));

        client.getStoreItems(java.util.List.of(730L));

        server.verify();
    }

    @Test
    void parsesTheReviewLevelPercentPositiveAndTagIds() {
        server.expect(requestTo(startsWith(BASE))).andRespond(withSuccess("""
                {"response":{"store_items":[
                  {"id":730,"success":1,"appid":730,"tagids":[1663,19,3859],
                   "reviews":{"summary_filtered":{"review_count":9899636,"percent_positive":85,"review_score":8,"review_score_label":"Very Positive"}}},
                  {"id":1422450,"success":1,"appid":1422450,"tagids":[1718],
                   "reviews":{"summary_filtered":{"review_count":0,"percent_positive":0,"review_score":0}}},
                  {"id":50,"success":1,"appid":50},
                  {"id":60,"success":1,"appid":60,"reviews":{"summary_filtered":{"review_count":5,"percent_positive":250,"review_score":42}}}
                ]}}
                """, MediaType.APPLICATION_JSON));

        var items = client.getStoreItems(java.util.List.of(730L, 1422450L, 50L, 60L));

        assertThat(items.get(730L).reviewScore()).isEqualTo(8);
        assertThat(items.get(730L).percentPositive()).isEqualTo(85);
        assertThat(items.get(730L).tagIds()).containsExactly(1663L, 19L, 3859L);
        assertThat(items.get(1422450L).reviewScore()).isZero();
        assertThat(items.get(1422450L).percentPositive()).as("no reviews: no percentage either").isNull();
        assertThat(items.get(50L).reviewScore()).isZero();
        assertThat(items.get(50L).tagIds()).isEmpty();
        assertThat(items.get(60L).reviewScore()).as("clamped into Steam's scale").isEqualTo(9);
        assertThat(items.get(60L).percentPositive()).isEqualTo(100);
    }

    @Test
    void parsesTheAgeRatingCodeSteamShowsAndLeavesItEmptyWhenThereIsNone() {
        server.expect(requestTo(startsWith(BASE))).andRespond(withSuccess("""
                {"response":{"store_items":[
                  {"id":292030,"success":1,"appid":292030,"game_rating":{"rating":"m","agency":1,"required_age":"17","descriptors":["Blood and Gore"]}},
                  {"id":105600,"success":1,"appid":105600},
                  {"id":70,"success":1,"appid":70,"game_rating":{"agency":1}}
                ]}}
                """, MediaType.APPLICATION_JSON));

        var items = client.getStoreItems(java.util.List.of(292030L, 105600L, 70L));

        assertThat(items.get(292030L).ageRating()).isEqualTo("m");
        assertThat(items.get(105600L).ageRating()).as("no rating on the store page").isNull();
        assertThat(items.get(70L).ageRating()).isNull();
    }

    @Test
    void buildsTheSquareIconPathFromTheCommunityIconHash() {
        server.expect(requestTo(startsWith(BASE))).andRespond(withSuccess("""
                {"response":{"store_items":[
                  {"id":730,"success":1,"appid":730,"assets":{"community_icon":"8dbc71957312bbd3baea65848b545be9eae2a355"}},
                  {"id":10,"success":1,"appid":10,"assets":{"header":"x/header.jpg"}}
                ]}}
                """, MediaType.APPLICATION_JSON));

        var items = client.getStoreItems(java.util.List.of(730L, 10L));

        assertThat(items.get(730L).iconPath()).isEqualTo("730/8dbc71957312bbd3baea65848b545be9eae2a355.jpg");
        assertThat(items.get(10L).iconPath()).as("no community icon in the answer").isNull();
    }

    @Test
    void refusesIconHashesThatAreNotPlain40CharacterLowercaseHex() {
        for (String bad : new String[]{"../../etc/passwd", "8DBC71957312BBD3BAEA65848B545BE9EAE2A355", "8dbc7195", "8dbc71957312bbd3baea65848b545be9eae2a355/../x",
                "8dbc71957312bbd3baea65848b545be9eae2a35g", "javascript:alert(1)", ""}) {
            var assets = new com.vandrae.patchnotes.externalapi.internal.SteamStoreItemsEnvelope.Assets(null, null, null, bad);
            assertThat(SteamStoreClient.iconPath(730, assets)).as(bad).isNull();
        }
        assertThat(SteamStoreClient.iconPath(730, null)).isNull();
    }

    @Test
    void refusesImagePathsThatCouldPointAnywhereButSteamsCdn() {
        server.expect(requestTo(startsWith(BASE))).andRespond(withSuccess(ITEMS, MediaType.APPLICATION_JSON));

        var items = client.getStoreItems(java.util.List.of(30L, 40L));

        assertThat(items.get(30L).imagePath()).as("protocol-relative URL to another host").isNull();
        assertThat(items.get(40L).imagePath()).as("path traversal").isNull();
        assertThat(items.get(30L).shortDescription()).isNull();
    }

    @Test
    void anAnswerWithoutAnyItemsIsEmptyNotAnError() {
        server.expect(requestTo(startsWith(BASE))).andRespond(withSuccess("{\"response\":{}}", MediaType.APPLICATION_JSON));

        assertThat(client.getStoreItems(java.util.List.of(1L))).isEmpty();
    }

    @Test
    void doesNothingForNoIdsAndRejectsRequestsSteamWouldRefuse() {
        assertThat(client.getStoreItems(java.util.List.of())).isEmpty(); // no request: the mock has no expectations

        var tooMany = LongStream.rangeClosed(1, SteamStoreClient.MAX_ITEMS_PER_REQUEST + 1L).boxed().toList();
        assertThatThrownBy(() -> client.getStoreItems(tooMany)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aFailingDetailsRequestNeverLeaksTheKey() {
        server.expect(ExpectedCount.times(4), requestTo(startsWith(BASE))).andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        assertThatThrownBy(() -> client.getStoreItems(java.util.List.of(1L)))
                .isInstanceOf(SteamApiException.class)
                .hasMessageContaining("HTTP 502")
                .hasMessageNotContaining(KEY)
                .hasNoCause();
    }

    // ------------------------------------------------------------------------- chart

    @Test
    void readsTheMostPlayedChart() {
        server.expect(requestTo(startsWith(BASE + "/ISteamChartsService/GetMostPlayedGames/v1/")))
                .andRespond(withSuccess("""
                        {"response":{"rollup_date":1790726400,"ranks":[
                          {"rank":1,"appid":730,"last_week_rank":1,"peak_in_game":1316372},
                          {"rank":8,"appid":1422450,"last_week_rank":21,"peak_in_game":185234},
                          {"rank":9,"peak_in_game":5}]}}
                        """, MediaType.APPLICATION_JSON));

        var chart = client.getMostPlayedGames();

        assertThat(chart).containsExactly(new SteamChartEntry(730, 1316372), new SteamChartEntry(1422450, 185234)); // the row without an appid is ignored
    }

    @Test
    void anEmptyChartIsJustEmpty() {
        server.expect(requestTo(startsWith(BASE))).andRespond(withSuccess("{\"response\":{}}", MediaType.APPLICATION_JSON));

        assertThat(client.getMostPlayedGames()).isEmpty();
    }

    @Test
    void imagePathHelperHandlesMissingPieces() {
        assertThat(SteamStoreClient.imagePath(null)).isNull();
        var noTemplate = new com.vandrae.patchnotes.externalapi.internal.SteamStoreItemsEnvelope.Assets(null, "a.jpg", null, null);
        var noFile = new com.vandrae.patchnotes.externalapi.internal.SteamStoreItemsEnvelope.Assets("steam/apps/1/${FILENAME}", null, null, null);
        assertThat(SteamStoreClient.imagePath(noTemplate)).isNull();
        assertThat(SteamStoreClient.imagePath(noFile)).isNull();
    }
}
