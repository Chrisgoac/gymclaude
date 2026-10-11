package com.chrisgoac.gymlog.data.ai

import com.chrisgoac.gymlog.data.coach.toDto
import com.chrisgoac.gymlog.database.UserDatabaseManager
import com.chrisgoac.gymlog.domain.ai.IntelligentReport
import com.chrisgoac.gymlog.domain.ai.IntelligentReportRepository
import com.chrisgoac.gymlog.domain.coach.CoachException
import com.chrisgoac.gymlog.domain.coach.CoachFailure
import com.chrisgoac.gymlog.domain.stats.CoachSnapshot
import com.chrisgoac.gymlog.model.ai.IntelligentReportRequestDto
import com.chrisgoac.gymlog.network.AiPlanApi
import com.chrisgoac.gymlog.network.SyncApiException

/**
 * [IntelligentReportRepository] over `POST /api/v1/ai/reports`. Reports are server-owned (cached there by the
 * fingerprint of the snapshot sent) and never stored in Room or synced; the last one fetched is kept in memory
 * for the account it belongs to, so the screen can show it with its date while offline. Nothing is logged.
 */
class OfflineIntelligentReportRepository(
    private val databases: UserDatabaseManager,
    private val api: AiPlanApi,
    private val snapshot: suspend () -> CoachSnapshot,
) : IntelligentReportRepository {
    private data class Last(val userId: String, val report: IntelligentReport)

    @Volatile private var last: Last? = null

    override suspend fun generate(): IntelligentReport {
        val open = databases.current.value ?: throw CoachException(CoachFailure.NO_ACCOUNT)
        val request = IntelligentReportRequestDto(snapshot().toDto())
        val response = try {
            api.intelligentReport(open.userId, request)
        } catch (e: SyncApiException) {
            throw e.toAiFailure()
        }
        val report = with(response.report) {
            IntelligentReport(response.reportId, response.generatedAt, response.cached, resumen, puntosFuertes, aMejorar, recomendaciones)
        }
        last = Last(open.userId, report)
        return report
    }

    override fun lastReport(): IntelligentReport? {
        val userId = databases.current.value?.userId ?: return null
        return last?.takeIf { it.userId == userId }?.report
    }
}
