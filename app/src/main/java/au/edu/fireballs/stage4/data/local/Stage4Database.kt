package au.edu.fireballs.stage4.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import au.edu.fireballs.stage4.data.local.dao.CandidateDao
import au.edu.fireballs.stage4.data.local.dao.ClaimDao
import au.edu.fireballs.stage4.data.local.dao.LocalDecisionDao
import au.edu.fireballs.stage4.data.local.dao.OfflineBundleDao
import au.edu.fireballs.stage4.data.local.dao.PendingPhotoUploadDao
import au.edu.fireballs.stage4.data.local.dao.SurveyDao
import au.edu.fireballs.stage4.data.local.dao.TileManifestDao

@Database(
    entities = [
        SurveyEntity::class,
        CandidateEntity::class,
        ClaimEntity::class,
        LocalDecisionEntity::class,
        PendingPhotoUploadEntity::class,
        OfflineBundleEntity::class,
        TileManifestEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class Stage4Database : RoomDatabase() {
    abstract fun surveyDao(): SurveyDao
    abstract fun candidateDao(): CandidateDao
    abstract fun claimDao(): ClaimDao
    abstract fun localDecisionDao(): LocalDecisionDao
    abstract fun pendingPhotoUploadDao(): PendingPhotoUploadDao
    abstract fun offlineBundleDao(): OfflineBundleDao
    abstract fun tileManifestDao(): TileManifestDao
}
