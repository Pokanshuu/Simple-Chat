package com.simplechat.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class FavoriteGroupModeTest {
    @Test fun parses_known_ids_and_falls_back_to_conversation() {
        assertEquals(FavoriteGroupMode.CONVERSATION, FavoriteGroupMode.fromId("CONVERSATION"))
        assertEquals(FavoriteGroupMode.FOLDER, FavoriteGroupMode.fromId("FOLDER"))
        assertEquals(FavoriteGroupMode.CONVERSATION, FavoriteGroupMode.fromId(null))
        assertEquals(FavoriteGroupMode.CONVERSATION, FavoriteGroupMode.fromId("BROKEN"))
    }
}
