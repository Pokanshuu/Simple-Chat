package com.simplechat.app.data.sync

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 三向比较的同步计划（docs/40 §50.7）。
 *
 * 计划错了的代价：该传的没传、该拉的被覆盖、删除传不出去 —— 全部是静默的数据
 * 不一致。所以这里把状态机逐条钉死。
 */
class SyncPlanTest {

    private val conv = "conv-1"
    private fun local(id: String = conv, hash: String = "h1"): List<LocalShard> =
        listOf(LocalShard(id, hash, File("$id.json")))
    private fun remote(id: String = conv, etag: String? = "e1", conversationId: String? = id) =
        RemoteShard(davFileName(id), etag, conversationId)
    private fun baseline(id: String = conv, hash: String = "h1", etag: String? = "e1") =
        SyncBaseline(hash, etag, davFileName(id), 1L)

    private fun state(
        baselines: Map<String, SyncBaseline> = emptyMap(),
        pending: List<SyncTombstoneRecord> = emptyList(),
    ) = SyncState(baselines = baselines, pendingTombstones = pending)

    // ── 上传 ─────────────────────────────────────────────

    @Test fun `new local conversation is uploaded with if-none-match`() {
        val plan = planSync((local()), setOf(conv), emptySet(), emptyList(), emptyList(), state())
        val push = plan.actions.filterIsInstance<SyncAction.Push>().single()
        assertEquals(PushReason.NEW, push.reason)
        assertTrue(push.ifNoneMatch)
        assertEquals(null, push.ifMatch)
        assertEquals(davFileName(conv), push.remoteFile)
    }

    @Test fun `locally changed with unchanged remote pushes with if-match`() {
        val plan = planSync(
            local(hash = "h2"), setOf(conv), emptySet(),
            listOf(remote(etag = "e1")), emptyList(),
            state(mapOf(conv to baseline(hash = "h1", etag = "e1"))),
        )
        val push = plan.actions.filterIsInstance<SyncAction.Push>().single()
        assertEquals(PushReason.LOCAL_CHANGED, push.reason)
        assertEquals("e1", push.ifMatch)
        assertTrue(plan.actions.none { it is SyncAction.Pull })
        assertTrue(plan.conflicts.isEmpty())
    }

    @Test fun `remote deleted without tombstone still uploads local copy`() {
        val plan = planSync(
            local(hash = "h1"), setOf(conv), emptySet(), emptyList(), emptyList(),
            state(mapOf(conv to baseline())),
        )
        assertEquals(PushReason.LOCAL_CHANGED, plan.actions.filterIsInstance<SyncAction.Push>().single().reason)
    }

    // ── 下载 ─────────────────────────────────────────────

    @Test fun `unchanged local with changed remote is pulled`() {
        val plan = planSync(
            local(hash = "h1"), setOf(conv), emptySet(),
            listOf(remote(etag = "e2")), emptyList(),
            state(mapOf(conv to baseline(etag = "e1"))),
        )
        val pull = plan.actions.filterIsInstance<SyncAction.Pull>().single()
        assertEquals("e2", pull.etag)
        assertTrue(plan.conflicts.isEmpty())
    }

    @Test fun `nothing changed is a no-op`() {
        val plan = planSync(
            local(), setOf(conv), emptySet(),
            listOf(remote(etag = "e1")), emptyList(),
            state(mapOf(conv to baseline(etag = "e1"))),
        )
        assertTrue(plan.actions.isEmpty())
        assertTrue(plan.conflicts.isEmpty())
    }

    @Test fun `remote without baseline is downloaded for comparison as a conflict`() {
        val plan = planSync(
            local(), setOf(conv), emptySet(),
            listOf(remote(etag = "e9")), emptyList(), state(),
        )
        assertTrue(plan.actions.any { it is SyncAction.PullCompare })
        assertEquals(SyncConflictKind.BOTH_CHANGED, plan.conflicts.single().kind)
    }

    @Test fun `unknown remote shard is pulled as new data`() {
        val plan = planSync(
            emptyList(), emptySet(), emptySet(),
            listOf(RemoteShard("abc.json", "e1", conversationId = null)), emptyList(), state(),
        )
        assertEquals("abc.json", plan.actions.filterIsInstance<SyncAction.PullUnknown>().single().remoteFile)
    }

    // ── 双改 / 删除传播 ──────────────────────────────────

    @Test fun `both changed downloads for comparison and records a conflict`() {
        val plan = planSync(
            local(hash = "h2"), setOf(conv), emptySet(),
            listOf(remote(etag = "e2")), emptyList(),
            state(mapOf(conv to baseline(hash = "h1", etag = "e1"))),
        )
        assertTrue(plan.actions.any { it is SyncAction.PullCompare })
        assertEquals(SyncConflictKind.BOTH_CHANGED, plan.conflicts.single().kind)
    }

    @Test fun `permanently deleted local conversation pushes tombstone and deletes remote`() {
        val plan = planSync(
            emptyList(), localIds = emptySet(), trashedIds = emptySet(),
            remote = listOf(remote()), remoteTombstones = emptyList(),
            state = state(mapOf(conv to baseline())),
        )
        assertEquals(conv, plan.actions.filterIsInstance<SyncAction.PushTombstone>().single().conversationId)
        assertEquals(davFileName(conv), plan.actions.filterIsInstance<SyncAction.DeleteRemote>().single().remoteFile)
    }

    @Test fun `conversation in trash is not tombstoned`() {
        val plan = planSync(
            emptyList(), localIds = setOf(conv), trashedIds = setOf(conv),
            remote = listOf(remote()), remoteTombstones = emptyList(),
            state = state(mapOf(conv to baseline())),
        )
        assertTrue(plan.actions.isEmpty())
    }

    @Test fun `pending tombstone is uploaded then baseline-side deletion skipped`() {
        val pending = SyncTombstoneRecord(conv, davFileName(conv), 2L)
        val plan = planSync(
            emptyList(), localIds = emptySet(), trashedIds = emptySet(),
            remote = listOf(remote()), remoteTombstones = emptyList(),
            state = state(pending = listOf(pending)),
        )
        // 只发一次（不会因为基线缺失/重复再发一遍）
        assertEquals(1, plan.actions.filterIsInstance<SyncAction.PushTombstone>().size)
        assertEquals(1, plan.actions.filterIsInstance<SyncAction.DeleteRemote>().size)
    }

    @Test fun `remote tombstone deletes unchanged local conversation`() {
        val plan = planSync(
            (local(hash = "h1")), setOf(conv), emptySet(),
            remote = emptyList(),
            remoteTombstones = listOf(RemoteTombstone("tomb.json", "e1", conv)),
            state = state(mapOf(conv to baseline(hash = "h1"))),
        )
        assertEquals(conv, plan.actions.filterIsInstance<SyncAction.DeleteLocal>().single().conversationId)
        assertTrue(plan.conflicts.isEmpty())
    }

    @Test fun `remote tombstone yields to local changes and records a conflict`() {
        val plan = planSync(
            (local(hash = "h2")), setOf(conv), emptySet(),
            remote = emptyList(),
            remoteTombstones = listOf(RemoteTombstone("tomb.json", "e1", conv)),
            state = state(mapOf(conv to baseline(hash = "h1"))),
        )
        assertEquals(PushReason.DELETE_WON, plan.actions.filterIsInstance<SyncAction.Push>().single().reason)
        assertEquals(SyncConflictKind.DELETED_REMOTE_KEPT_LOCAL, plan.conflicts.single().kind)
        assertTrue(plan.actions.none { it is SyncAction.DeleteLocal })
    }

    @Test fun `remote tombstone for trashed local conversation is ignored`() {
        val plan = planSync(
            emptyList(), setOf(conv), setOf(conv),
            remote = emptyList(),
            remoteTombstones = listOf(RemoteTombstone("tomb.json", "e1", conv)),
            state = state(mapOf(conv to baseline(hash = "h1"))),
        )
        assertTrue(plan.actions.none { it is SyncAction.DeleteLocal })
    }
}
