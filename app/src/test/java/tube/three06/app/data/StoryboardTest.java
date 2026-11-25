package tube.three06.app.data;

/** Standalone regression checks; run with assertions enabled. */
public final class StoryboardTest {
    public static void main(String[] args) {
        String spec = "https://i9.ytimg.com/sb/id/storyboard3_L$L/$N.jpg?sqp=abc"
                + "|48#27#100#10#10#0#default#sig0"
                + "|160#90#102#5#5#10000#M$M#sig2";
        StoryboardFrame frame = StoryboardKt.storyboardFrame(spec, "");
        if (frame == null || !frame.getUrl().contains("storyboard3_L1/M0.jpg")
                || !frame.getUrl().contains("sigh=sig2") || frame.getX() != 0
                || frame.getY() != 180 || frame.getWidth() != 160 || frame.getHeight() != 90) {
            throw new AssertionError("incorrect frame or sheet selection");
        }
        if (StoryboardKt.storyboardFrame("invalid", "id") != null
                || StoryboardKt.storyboardFrame(spec.replace("i9.ytimg.com", "evil.example"), "id") != null
                || StoryboardKt.storyboardFrame("https://i.ytimg.com/$N|99999#90#100#5#5#0#M$M#sig", "id") != null) {
            throw new AssertionError("invalid storyboard accepted");
        }
        System.out.println("Storyboard selection checks passed");
    }
}
