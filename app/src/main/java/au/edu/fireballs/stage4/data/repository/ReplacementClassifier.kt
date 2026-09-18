package au.edu.fireballs.stage4.data.repository

object ReplacementClassifier {
    fun classify(
        target: PreDownloadTargetSet,
        owned: Collection<PreDownloadOwnedPayload>,
    ): ReplacementClassification {
        val required = target.all
        val validKeys =
            owned
                .filter { it.valid }
                .mapTo(mutableSetOf()) { it.key }
        val retained = required.filterTo(mutableSetOf()) { it in validKeys }
        val missing = required.filterTo(mutableSetOf()) { it !in retained }
        val obsoleteOwned = owned.filter { it.key !in retained }
        val obsolete = obsoleteOwned.mapTo(mutableSetOf()) { it.key.toPruneKey() }
        val confidentlyDeletableBytes =
            obsoleteOwned
                .filter { it.key !is PreDownloadTargetKey.Satellite }
                .sumOf { it.measuredDeletableBytes ?: 0L }
        val clearCommands =
            if (retained.isEmpty() && required.isNotEmpty()) {
                buildClearCommands(obsolete)
            } else {
                emptyList()
            }
        return ReplacementClassification(
            retained = retained,
            missing = missing,
            obsolete = obsolete,
            clearCommands = clearCommands,
            confidentlyDeletableBytes = confidentlyDeletableBytes,
        )
    }

    private fun buildClearCommands(
        obsolete: Set<PreDownloadPruneKey>,
    ): List<PreDownloadClearCommand> =
        buildList {
            if (obsolete.any { it is PreDownloadPruneKey.Geotiff }) {
                add(PreDownloadClearCommand.ClearGeotiffs)
            }
            if (obsolete.any { it is PreDownloadPruneKey.Crop }) {
                add(PreDownloadClearCommand.ClearCrops)
            }
            if (obsolete.any { it is PreDownloadPruneKey.Satellite }) {
                add(PreDownloadClearCommand.ClearSatelliteRegions)
            }
        }

    fun PreDownloadTargetKey.toPruneKey(): PreDownloadPruneKey =
        when (this) {
            is PreDownloadTargetKey.Tile ->
                PreDownloadPruneKey.Geotiff(surveyId, candidateId, zoom, x, y)

            is PreDownloadTargetKey.Crop ->
                PreDownloadPruneKey.Crop(surveyId, candidateId)

            is PreDownloadTargetKey.Satellite ->
                PreDownloadPruneKey.Satellite(surveyId, signature)
        }
}
