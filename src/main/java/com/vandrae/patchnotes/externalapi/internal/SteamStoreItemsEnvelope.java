package com.vandrae.patchnotes.externalapi.internal;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Wire format of IStoreBrowseService/GetItems/v1. Every field is optional (wrapper types): Steam leaves out whole
 * sections for apps without a store page, and a missing number must not fail the whole batch.
 */
public record SteamStoreItemsEnvelope(Response response) {

    public record Response(@JsonProperty("store_items") List<Item> storeItems) {
    }

    public record Item(Long id, Long appid, Integer success,
                       @JsonProperty("basic_info") BasicInfo basicInfo,
                       @JsonProperty("tagids") List<Long> tagIds,
                       Reviews reviews,
                       Assets assets) {
    }

    public record BasicInfo(@JsonProperty("short_description") String shortDescription) {
    }

    public record Reviews(@JsonProperty("summary_filtered") Summary summaryFiltered) {
    }

    public record Summary(@JsonProperty("review_count") Integer reviewCount,
                         @JsonProperty("percent_positive") Integer percentPositive,
                         @JsonProperty("review_score") Integer reviewScore) {
    }

    public record Assets(@JsonProperty("asset_url_format") String assetUrlFormat,
                         @JsonProperty("small_capsule") String smallCapsule,
                         String header,
                         @JsonProperty("community_icon") String communityIcon) {
    }
}
