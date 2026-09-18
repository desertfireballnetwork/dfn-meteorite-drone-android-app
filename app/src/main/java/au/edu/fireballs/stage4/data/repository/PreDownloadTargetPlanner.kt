package au.edu.fireballs.stage4.data.repository

import au.edu.fireballs.stage4.data.tiles.Bbox
import au.edu.fireballs.stage4.data.tiles.TileCoord
import au.edu.fireballs.stage4.data.tiles.TileMath
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class PreDownloadTargetCandidate(
    val inferenceResultId: Long,
    val lat: Double,
    val lon: Double,
)

data class PreDownloadSatelliteTarget(
    val bbox: Bbox,
    val signature: String,
)

object PreDownloadTargetPlanner {
    const val TILE_MIN_ZOOM = 20
    const val TILE_MAX_ZOOM = 22
    const val SATELLITE_MIN_ZOOM = 18
    const val SATELLITE_MAX_ZOOM = 22
    const val GEOTIFF_KIND = "GEOTIFF"
    const val GEOTIFF_EXPECTED_FORMAT = "PNG"

    fun cluster(
        candidates: List<PreDownloadTargetCandidate>,
        bufferRadiusMeters: Double,
    ): List<List<PreDownloadTargetCandidate>> {
        val parent = IntArray(candidates.size) { it }
        val rank = IntArray(candidates.size)

        fun find(index: Int): Int {
            var current = index
            while (parent[current] != current) {
                parent[current] = parent[parent[current]]
                current = parent[current]
            }
            return current
        }

        fun union(
            first: Int,
            second: Int,
        ) {
            val firstRoot = find(first)
            val secondRoot = find(second)
            if (firstRoot == secondRoot) return
            when {
                rank[firstRoot] < rank[secondRoot] -> parent[firstRoot] = secondRoot
                rank[firstRoot] > rank[secondRoot] -> parent[secondRoot] = firstRoot
                else -> {
                    parent[secondRoot] = firstRoot
                    rank[firstRoot]++
                }
            }
        }

        for (i in candidates.indices) {
            for (j in i + 1 until candidates.size) {
                if (distanceMeters(candidates[i], candidates[j]) <
                    2.0 * bufferRadiusMeters
                ) {
                    union(i, j)
                }
            }
        }

        val groups = mutableMapOf<Int, MutableList<PreDownloadTargetCandidate>>()
        candidates.forEachIndexed { index, candidate ->
            groups.getOrPut(find(index)) { mutableListOf() }.add(candidate)
        }
        return groups.values.toList()
    }

    fun unionBbox(
        cluster: List<PreDownloadTargetCandidate>,
        bufferRadiusMeters: Double,
    ): Bbox {
        var minLat = Double.POSITIVE_INFINITY
        var minLon = Double.POSITIVE_INFINITY
        var maxLat = Double.NEGATIVE_INFINITY
        var maxLon = Double.NEGATIVE_INFINITY
        cluster.forEach { candidate ->
            val bbox =
                TileMath.bufferBbox(
                    candidate.lat,
                    candidate.lon,
                    bufferRadiusMeters,
                )
            minLat = minOf(minLat, bbox.minLat)
            minLon = minOf(minLon, bbox.minLon)
            maxLat = maxOf(maxLat, bbox.maxLat)
            maxLon = maxOf(maxLon, bbox.maxLon)
        }
        return Bbox(minLat, minLon, maxLat, maxLon)
    }

    fun satelliteSignature(
        cluster: List<PreDownloadTargetCandidate>,
        bbox: Bbox,
    ): String =
        buildString {
            append("sat:")
            append(cluster.map { it.inferenceResultId }.sorted().joinToString(","))
            append(':')
            append(bbox.minLat.toBits())
            append(',')
            append(bbox.minLon.toBits())
            append(',')
            append(bbox.maxLat.toBits())
            append(',')
            append(bbox.maxLon.toBits())
            append(':')
            append(SATELLITE_MIN_ZOOM)
            append('-')
            append(SATELLITE_MAX_ZOOM)
        }

    fun satelliteTargets(
        clusters: List<List<PreDownloadTargetCandidate>>,
        bufferRadiusMeters: Double,
    ): List<PreDownloadSatelliteTarget> =
        clusters.map { cluster ->
            val bbox = unionBbox(cluster, bufferRadiusMeters)
            PreDownloadSatelliteTarget(bbox, satelliteSignature(cluster, bbox))
        }

    fun tileKeysForCandidate(
        surveyId: Long,
        candidate: PreDownloadTargetCandidate,
        radiusMeters: Double,
        sourceVersion: String,
        kind: String = GEOTIFF_KIND,
        expectedFormat: String = GEOTIFF_EXPECTED_FORMAT,
    ): List<PreDownloadTargetKey.Tile> =
        tilesForCandidate(candidate, radiusMeters).map { coord ->
            PreDownloadTargetKey.Tile(
                surveyId = surveyId,
                candidateId = candidate.inferenceResultId,
                sourceVersion = sourceVersion,
                radiusMetres = radiusMeters,
                zoom = coord.z,
                x = coord.x,
                y = coord.y,
                kind = kind,
                expectedFormat = expectedFormat,
            )
        }

    fun cropRequestSignature(
        surveyId: Long,
        candidateId: Long,
    ): String = "crop:$surveyId:$candidateId"

    fun cropKey(
        surveyId: Long,
        candidateId: Long,
        sourceVersion: String,
    ): PreDownloadTargetKey.Crop =
        PreDownloadTargetKey.Crop(
            surveyId = surveyId,
            candidateId = candidateId,
            sourceVersion = sourceVersion,
            requestSignature = cropRequestSignature(surveyId, candidateId),
        )

    fun satelliteKey(
        surveyId: Long,
        sourceVersion: String,
        signature: String,
    ): PreDownloadTargetKey.Satellite =
        PreDownloadTargetKey.Satellite(
            surveyId = surveyId,
            sourceVersion = sourceVersion,
            signature = signature,
        )

    fun targetSet(
        surveyId: Long,
        sourceVersion: String,
        candidates: List<PreDownloadTargetCandidate>,
        bufferRadiusMeters: Double,
        geotiffRadiusMeters: Double,
    ): PreDownloadTargetSet {
        val clusters = cluster(candidates, bufferRadiusMeters)
        return PreDownloadTargetSet(
            geotiffTiles =
                candidates.flatMap { candidate ->
                    tileKeysForCandidate(
                        surveyId,
                        candidate,
                        geotiffRadiusMeters,
                        sourceVersion,
                    )
                },
            crops =
                candidates.map { candidate ->
                    cropKey(surveyId, candidate.inferenceResultId, sourceVersion)
                },
            satellites =
                satelliteTargets(clusters, bufferRadiusMeters).map { target ->
                    satelliteKey(surveyId, sourceVersion, target.signature)
                },
        )
    }

    fun tilesForCandidate(
        candidate: PreDownloadTargetCandidate,
        geotiffRadiusMeters: Double,
    ): List<TileCoord> {
        val bbox =
            TileMath.bufferBbox(
                candidate.lat,
                candidate.lon,
                geotiffRadiusMeters,
            )
        return (TILE_MIN_ZOOM..TILE_MAX_ZOOM).flatMap { zoom ->
            TileMath.tilesForBbox(
                bbox.minLat,
                bbox.minLon,
                bbox.maxLat,
                bbox.maxLon,
                zoom,
            )
        }
    }

    private fun distanceMeters(
        first: PreDownloadTargetCandidate,
        second: PreDownloadTargetCandidate,
    ): Double {
        val dLat = Math.toRadians(second.lat - first.lat)
        val dLon = Math.toRadians(second.lon - first.lon)
        val firstLat = Math.toRadians(first.lat)
        val secondLat = Math.toRadians(second.lat)
        val h =
            sin(dLat / 2.0) * sin(dLat / 2.0) +
                cos(firstLat) * cos(secondLat) *
                sin(dLon / 2.0) * sin(dLon / 2.0)
        return 2.0 * EARTH_RADIUS_METERS * asin(sqrt(h))
    }

    private const val EARTH_RADIUS_METERS = 6_371_000.0
}
