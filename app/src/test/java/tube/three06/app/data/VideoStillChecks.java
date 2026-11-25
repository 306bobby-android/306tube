package tube.three06.app.data;

/** Standalone checks for YouTube still URL selection. */
public final class VideoStillChecks {
    public static void main(String[] args) {
        String id = "dQw4w9WgXcQ";
        String url = VideoStillKt.videoStillUrl(id);
        if (url == null || !url.matches("https://i\\.ytimg\\.com/vi/" + id + "/sd[1-3]\\.jpg")
                || !url.equals(VideoStillKt.videoStillUrl(id))) {
            throw new AssertionError("expected a deterministic YouTube still URL");
        }
        if (VideoStillKt.videoStillUrl("../bad") != null || VideoStillKt.videoStillUrl("") != null) {
            throw new AssertionError("invalid video ID accepted");
        }
        System.out.println("Video still URL checks passed");
    }
}
