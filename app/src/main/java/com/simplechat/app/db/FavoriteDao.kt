package com.simplechat.app.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoriteDao {
    @Query("SELECT * FROM favorites ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<FavoriteEntity>>

    @Query("SELECT * FROM favorites ORDER BY createdAt DESC")
    suspend fun all(): List<FavoriteEntity>

    @Query("SELECT * FROM favorites WHERE messageId = :messageId LIMIT 1")
    suspend fun byMessage(messageId: String): FavoriteEntity?

    @Query("SELECT * FROM favorites WHERE id = :id")
    suspend fun byId(id: String): FavoriteEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(favorite: FavoriteEntity): Long

    @Update
    suspend fun update(favorite: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM favorites WHERE messageId = :messageId")
    suspend fun deleteByMessage(messageId: String)

    @Query("UPDATE favorites SET messageId = NULL WHERE conversationId IN (:conversationIds)")
    suspend fun detachConversations(conversationIds: List<String>)

    @Query("UPDATE favorites SET messageId = NULL WHERE messageId = :messageId")
    suspend fun detachMessage(messageId: String)

    @Query(
        "SELECT * FROM favorites WHERE id IN " +
            "(SELECT favoriteId FROM favorite_memberships WHERE folderId = :folderId) " +
            "ORDER BY createdAt DESC",
    )
    fun observeInFolder(folderId: String): Flow<List<FavoriteEntity>>

    @Query(
        "SELECT * FROM favorites WHERE " +
            "instr(lower(content), lower(:query)) > 0 OR " +
            "instr(lower(COALESCE(note, '')), lower(:query)) > 0 OR " +
            "instr(lower(conversationTitle), lower(:query)) > 0 " +
            "ORDER BY createdAt DESC LIMIT 200",
    )
    suspend fun search(query: String): List<FavoriteEntity>

    @Query("SELECT COUNT(*) FROM favorites")
    suspend fun count(): Int
}

@Dao
interface FavoriteFolderDao {
    @Query("SELECT * FROM favorite_folders ORDER BY sortOrder ASC, createdAt ASC")
    fun observeAll(): Flow<List<FavoriteFolderEntity>>

    @Query("SELECT * FROM favorite_folders ORDER BY sortOrder ASC, createdAt ASC")
    suspend fun all(): List<FavoriteFolderEntity>

    @Query("SELECT * FROM favorite_folders WHERE id = :id")
    suspend fun byId(id: String): FavoriteFolderEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(folder: FavoriteFolderEntity): Long

    @Query("UPDATE favorite_folders SET name = :name, updatedAt = :now WHERE id = :id")
    suspend fun rename(id: String, name: String, now: Long)

    @Query("DELETE FROM favorite_folders WHERE id = :id AND id != 'default'")
    suspend fun delete(id: String)

    @Query("SELECT COUNT(*) FROM favorite_folders")
    suspend fun count(): Int
}

@Dao
interface FavoriteMembershipDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(membership: FavoriteMembershipEntity)

    @Query("DELETE FROM favorite_memberships WHERE favoriteId = :favoriteId AND folderId = :folderId")
    suspend fun remove(favoriteId: String, folderId: String)

    @Query("DELETE FROM favorite_memberships WHERE favoriteId = :favoriteId")
    suspend fun clear(favoriteId: String)

    @Query("DELETE FROM favorite_memberships WHERE folderId = :folderId")
    suspend fun clearFolder(folderId: String)

    @Query("SELECT * FROM favorite_memberships")
    suspend fun all(): List<FavoriteMembershipEntity>

    @Query("SELECT * FROM favorite_memberships")
    fun observeAll(): Flow<List<FavoriteMembershipEntity>>

    @Query("SELECT * FROM favorite_memberships WHERE favoriteId = :favoriteId")
    suspend fun ofFavorite(favoriteId: String): List<FavoriteMembershipEntity>

    @Query("SELECT * FROM favorite_memberships WHERE folderId = :folderId")
    suspend fun ofFolder(folderId: String): List<FavoriteMembershipEntity>
}

@Dao
interface FavoriteMediaDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(media: FavoriteMediaEntity)

    @Query("DELETE FROM favorite_media WHERE favoriteId = :favoriteId")
    suspend fun clear(favoriteId: String)

    @Query("SELECT path FROM favorite_media WHERE favoriteId = :favoriteId")
    suspend fun pathsOf(favoriteId: String): List<String>

    @Query("SELECT COUNT(*) FROM favorite_media WHERE path = :path")
    suspend fun references(path: String): Int
}
