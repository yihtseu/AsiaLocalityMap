package asia.locality.map

/** Ink-on-paper colours shared by the live layer and the boundary raster. */
internal object InkPalette {
    const val LAND = 0xffbbbbbb.toInt()
    const val BOUNDARY_MAJOR = 0xff777777.toInt()
    const val BOUNDARY_MINOR = 0xffb0b0b0.toInt()
    const val GHOST_BOUNDARY = 0x998c6d58.toInt()
    const val GUIDE_LINE = 0x885c5c5c.toInt()
    const val SEAL = 0xff9e3a2b.toInt()
    const val SELECTION = 0xff242424.toInt()
    const val LABEL_SELECTED = 0xff111111.toInt()
    const val LABEL = 0xff4c4c4c.toInt()
    const val LABEL_SECONDARY = 0xff777777.toInt()
    const val SCALE = 0xff999999.toInt()
    const val HERE = 0xff242424.toInt()
}

/** Tunable map metrics; sizes are in dp unless noted. */
internal object MapMetrics {
    const val ZOOM_STEP = 1.6
    const val MIN_SPAN = 0.04
    const val MAX_SPAN = 100.0
    const val LABEL_SIZE_MAJOR = 18f
    const val LABEL_SIZE_MINOR = 16f
    const val LABEL_SIZE_SELECTED = 20f
    const val LABEL_SIZE_SECONDARY = 11f
    const val LABEL_HIT_SLOP = 8f
    const val MAX_VISIBLE_LABELS = 65
    const val MAX_STABLE_LABELS = 130
    /** Panning keeps the view centre inside the region the data can cover. */
    const val MIN_LON = 30.0
    const val MAX_LON = 180.0
    const val MIN_LAT = -15.0
    const val MAX_LAT = 70.0
    const val METERS_PER_DEGREE = 111_320.0
}
