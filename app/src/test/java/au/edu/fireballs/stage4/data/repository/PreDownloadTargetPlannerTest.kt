package au.edu.fireballs.stage4.data.repository

import au.edu.fireballs.stage4.data.tiles.TileMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreDownloadTargetPlannerTest {
    @Test
    fun `far candidates form separate clusters`() {
        val candidates =
            listOf(
                PreDownloadTargetCandidate(1L, -37.0, 145.0),
                PreDownloadTargetCandidate(2L, -38.0, 146.0),
            )

        val clusters = PreDownloadTargetPlanner.cluster(candidates, 100.0)

        assertEquals(2, clusters.size)
    }

    @Test
    fun `near candidates form one cluster`() {
        val candidates =
            listOf(
                PreDownloadTargetCandidate(1L, -37.0, 145.0),
                PreDownloadTargetCandidate(2L, -37.0005, 145.0),
            )

        val clusters = PreDownloadTargetPlanner.cluster(candidates, 100.0)

        assertEquals(1, clusters.size)
        assertEquals(candidates, clusters.single())
    }

    @Test
    fun `union bbox contains every buffered candidate bbox`() {
        val candidates =
            listOf(
                PreDownloadTargetCandidate(1L, -37.0, 145.0),
                PreDownloadTargetCandidate(2L, -37.001, 145.002),
            )
        val individual = candidates.map { TileMath.bufferBbox(it.lat, it.lon, 100.0) }

        val bbox = PreDownloadTargetPlanner.unionBbox(candidates, 100.0)

        assertEquals(individual.minOf { it.minLat }, bbox.minLat, 0.0)
        assertEquals(individual.minOf { it.minLon }, bbox.minLon, 0.0)
        assertEquals(individual.maxOf { it.maxLat }, bbox.maxLat, 0.0)
        assertEquals(individual.maxOf { it.maxLon }, bbox.maxLon, 0.0)
    }

    @Test
    fun `satellite signature is stable and sorts candidate ids`() {
        val first =
            listOf(
                PreDownloadTargetCandidate(20L, -37.0, 145.0),
                PreDownloadTargetCandidate(10L, -37.001, 145.001),
            )
        val second = first.reversed()
        val bbox = PreDownloadTargetPlanner.unionBbox(first, 100.0)

        val firstSignature = PreDownloadTargetPlanner.satelliteSignature(first, bbox)
        val secondSignature = PreDownloadTargetPlanner.satelliteSignature(second, bbox)

        assertEquals(firstSignature, secondSignature)
        assertTrue(firstSignature.startsWith("sat:10,20:"))
        assertTrue(firstSignature.endsWith(":18-22"))
    }

    @Test
    fun `candidate tiles match tile math across configured zooms`() {
        val candidate = PreDownloadTargetCandidate(1L, -37.0, 145.0)
        val radius = 10.0
        val bbox = TileMath.bufferBbox(candidate.lat, candidate.lon, radius)
        val expected =
            (20..22).flatMap { zoom ->
                TileMath.tilesForBbox(
                    bbox.minLat,
                    bbox.minLon,
                    bbox.maxLat,
                    bbox.maxLon,
                    zoom,
                )
            }

        val actual = PreDownloadTargetPlanner.tilesForCandidate(candidate, radius)

        assertEquals(expected, actual)
    }
}
