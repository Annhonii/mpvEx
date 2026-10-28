package app.marlboroadvance.mpvex.database

import androidx.room.Database
import androidx.room.RoomDatabase
import app.marlboroadvance.mpvex.database.dao.PlaybackStateDao
import app.marlboroadvance.mpvex.database.dao.PlaylistDao
import app.marlboroadvance.mpvex.database.dao.RecentlyPlayedDao
import app.marlboroadvance.mpvex.database.dao.VideoMetadataDao
import app.marlboroadvance.mpvex.database.entities.PlaybackStateEntity
import app.marlboroadvance.mpvex.database.entities.PlaylistEntity
import app.marlboroadvance.mpvex.database.entities.PlaylistItemEntity
import app.marlboroadvance.mpvex.database.entities.RecentlyPlayedEntity
import app.marlboroadvance.mpvex.database.entities.VideoMetadataEntity

@Database(
  entities = [
    PlaybackStateEntity::class,
    RecentlyPlayedEntity::class,
    VideoMetadataEntity::class,
    PlaylistEntity::class,
    PlaylistItemEntity::class,
  ],
  version = 9,
  exportSchema = true,
)
abstract class MpvExDatabase : RoomDatabase() {
  abstract fun videoDataDao(): PlaybackStateDao

  abstract fun recentlyPlayedDao(): RecentlyPlayedDao

  abstract fun videoMetadataDao(): VideoMetadataDao

  abstract fun playlistDao(): PlaylistDao
}
