package tube.three06.innertube.internal;

import kotlinx.serialization.json.Json;
import tube.three06.innertube.model.Page;
import tube.three06.innertube.model.Video;

/** Standalone regression checks for playlist page parsing. */
public final class PlaylistParsingChecks {
    public static void main(String[] args) {
        String initial = "{\"contents\":{\"twoColumnBrowseResultsRenderer\":{\"tabs\":[{\"tabRenderer\":{\"selected\":true,\"content\":{\"sectionListRenderer\":{\"contents\":[{\"itemSectionRenderer\":{\"contents\":[{\"playlistVideoListRenderer\":{\"contents\":[{\"playlistVideoRenderer\":{\"videoId\":\"newest\",\"title\":{\"simpleText\":\"Newest\"}}},{\"playlistVideoRenderer\":{\"videoId\":\"older\",\"title\":{\"simpleText\":\"Older\"}}},{\"continuationItemRenderer\":{\"continuationEndpoint\":{\"continuationCommand\":{\"token\":\"next-page\"}}}}]}}]}}]}}}}]}}}";
        Page<?> page = ParsersKt.pageOf(Json.Default.parseToJsonElement(initial));
        if (page.getItems().size() != 2 || !((Video) page.getItems().get(0)).getId().equals("newest")
                || !((Video) page.getItems().get(1)).getId().equals("older")
                || !"next-page".equals(page.getContinuation())) {
            throw new AssertionError("initial playlist items or continuation missing: " + page);
        }
        String continuation = "{\"onResponseReceivedActions\":[{\"appendContinuationItemsAction\":{\"continuationItems\":[{\"playlistVideoRenderer\":{\"videoId\":\"later\",\"title\":{\"simpleText\":\"Later\"}}},{\"continuationItemRenderer\":{\"continuationEndpoint\":{\"continuationCommand\":{\"token\":\"another-page\"}}}}]}}]}";
        Page<?> next = ParsersKt.pageOf(Json.Default.parseToJsonElement(continuation));
        if (next.getItems().size() != 1 || !((Video) next.getItems().get(0)).getId().equals("later")
                || !"another-page".equals(next.getContinuation())) {
            throw new AssertionError("continuation items or next token missing: " + next);
        }
        String watched = "{\"onResponseReceivedActions\":[{\"appendContinuationItemsAction\":{\"continuationItems\":[{\"playlistVideoRenderer\":{\"videoId\":\"done\",\"setVideoId\":\"entry-1\",\"isWatched\":true}},{\"playlistVideoRenderer\":{\"videoId\":\"partial\",\"setVideoId\":\"entry-2\",\"thumbnailOverlays\":[{\"thumbnailOverlayResumePlaybackRenderer\":{\"percentDurationWatched\":99}}]}},{\"playlistVideoRenderer\":{\"videoId\":\"complete\",\"setVideoId\":\"entry-3\",\"thumbnailOverlays\":[{\"thumbnailOverlayResumePlaybackRenderer\":{\"percentDurationWatched\":100}}]}},{\"playlistVideoRenderer\":{\"videoId\":\"badge\",\"thumbnailOverlays\":[{\"thumbnailOverlayPlaybackStatusRenderer\":{\"text\":{\"simpleText\":\"WATCHED\"}}}]}}]}}]}";
        Page<?> watchedPage = ParsersKt.pageOf(Json.Default.parseToJsonElement(watched));
        Video done = (Video) watchedPage.getItems().get(0);
        if (!done.isWatched() || !"entry-1".equals(done.getSetVideoId())
                || ((Video) watchedPage.getItems().get(1)).isWatched()
                || !((Video) watchedPage.getItems().get(2)).isWatched()
                || !((Video) watchedPage.getItems().get(3)).isWatched()) {
            throw new AssertionError("watched status or playlist entry ID was parsed incorrectly");
        }
        System.out.println("Playlist parsing checks passed");
    }
}
