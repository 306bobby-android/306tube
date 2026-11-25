package tube.three06.app.ui.playlist;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import tube.three06.innertube.model.Video;

/** Standalone checks for Watch Later filtering and ordering. */
public final class WatchLaterFiltersChecks {
    private static Video video(String id, Long duration) {
        return new Video(id, id, null, Collections.emptyList(), duration, null, null,
                false, false, false, null, null, Collections.emptyList(), false, null);
    }

    private static void expect(List<Video> actual, Video... expected) {
        if (!actual.equals(Arrays.asList(expected))) throw new AssertionError(actual.toString());
    }

    public static void main(String[] args) {
        Video unknown = video("unknown", null);
        Video boundary = video("15m", 900L);
        Video shortVideo = video("short", 899L);
        Video hour = video("hour", 3600L);
        List<Video> videos = Arrays.asList(unknown, boundary, shortVideo, hour);
        expect(WatchLaterFiltersKt.filterWatchLater(videos, WatchLaterSort.PLAYLIST, WatchLaterDuration.ALL),
                unknown, boundary, shortVideo, hour);
        expect(WatchLaterFiltersKt.filterWatchLater(videos, WatchLaterSort.SHORTEST, WatchLaterDuration.ALL),
                shortVideo, boundary, hour, unknown);
        expect(WatchLaterFiltersKt.filterWatchLater(videos, WatchLaterSort.LONGEST, WatchLaterDuration.ALL),
                hour, boundary, shortVideo, unknown);
        expect(WatchLaterFiltersKt.filterWatchLater(videos, WatchLaterSort.PLAYLIST, WatchLaterDuration.UNDER_15), shortVideo);
        expect(WatchLaterFiltersKt.filterWatchLater(videos, WatchLaterSort.PLAYLIST, WatchLaterDuration.FROM_15_TO_30), boundary);
        expect(WatchLaterFiltersKt.filterWatchLater(videos, WatchLaterSort.PLAYLIST, WatchLaterDuration.OVER_60), hour);
        System.out.println("Watch Later filtering checks passed");
    }
}
