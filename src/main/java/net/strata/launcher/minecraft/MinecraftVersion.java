package net.strata.launcher.minecraft;

public record MinecraftVersion(
        String id,
        String type,
        String url,
        String time,
        String releaseTime,
        String sha1,
        int complianceLevel) {

    public String displayName() {
        String date = releaseTime == null || releaseTime.length() < 10
                ? "" : " · " + releaseTime.substring(0, 10);
        return id + " · " + type + date;
    }

    @Override
    public String toString() {
        return displayName();
    }
}
