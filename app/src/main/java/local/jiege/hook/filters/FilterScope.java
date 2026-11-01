package local.jiege.hook.filters;

/**
 * The independently managed filter scopes. Each has its own catalog and its own filter groups
 * (static fields of both FilterGroupManager classes, SDK and app).
 */
public enum FilterScope {
    /** Every photo mode except master: photo, portrait, sticker, night. */
    NORMAL("sFilterGroup", "sPortraitFilterGroup", "sStickerFilterGroup", "sNightFilterGroup"),
    /** Master mode (mode name "professional"), all gears; its only group is the Pro group. */
    MASTER("sProFilterGroup"),
    /**
     * Normal video (mode name "commonVideo"). Stock fills its group from the IPU filter list only
     * (19 entries); the catalog adds the master LUTs on top so the same looks can be picked while
     * recording (see VideoFilterLuts for the table format the video renderer reads).
     */
    VIDEO("sVideoFilterGroup");

    /** Mode name of master mode (BaseMode.T0). */
    public static final String MASTER_MODE = "professional";
    /** Mode name of normal video. */
    public static final String VIDEO_MODE = "commonVideo";

    final String[] groups;

    FilterScope(String... groups) {
        this.groups = groups;
    }

    /** The scope whose filters a mode shows. */
    public static FilterScope ofMode(Object modeName) {
        if (MASTER_MODE.equals(modeName)) return MASTER;
        if (VIDEO_MODE.equals(modeName)) return VIDEO;
        return NORMAL;
    }
}
