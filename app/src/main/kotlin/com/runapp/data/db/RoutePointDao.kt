package com.runapp.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * DAO para operações de persistência de pontos GPS.
 *
 * Todas as operações são suspend — o Room garante que rodam em IO thread
 * quando chamadas de Dispatchers.IO ou viewModelScope/serviceScope.
 */
@Dao
interface RoutePointDao {

    /**
     * Insere um ponto GPS da corrida ativa.
     * Chamado a cada update do GPS (~1/s) pelo RunningService.
     * REPLACE garante idempotência (nunca duplica em restart).
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(point: RoutePointEntity)

    /**
     * Busca todos os pontos de uma sessão em ordem cronológica.
     * Usado para: (1) recuperação após process death, (2) cálculo final de métricas.
     */
    @Query("SELECT * FROM route_points WHERE sessionId = :sessionId ORDER BY tempo ASC")
    suspend fun getSessionPoints(sessionId: String): List<RoutePointEntity>

    /**
     * Retorna o sessionId mais recente (maior tempo de início).
     * Usado pelo ViewModel para encontrar a sessão a recuperar após crash.
     */
    @Query("SELECT sessionId FROM route_points ORDER BY tempo DESC LIMIT 1")
    suspend fun getLatestSessionId(): String?

    /**
     * Conta pontos de uma sessão — usado para validar se vale recuperar.
     */
    @Query("SELECT COUNT(*) FROM route_points WHERE sessionId = :sessionId")
    suspend fun countSessionPoints(sessionId: String): Int

    /**
     * Remove dados de sessões OLD — chamado após salvar corretamente no servidor.
     * Mantém banco leve; sessions antigas não têm valor após upload.
     */
    @Query("DELETE FROM route_points WHERE sessionId != :keepSessionId")
    suspend fun deleteOtherSessions(keepSessionId: String)

    /**
     * Remove TODOS os pontos de uma sessão específica.
     * Chamado ao descartar uma corrida intencionalmente.
     */
    @Query("DELETE FROM route_points WHERE sessionId = :sessionId")
    suspend fun deleteSession(sessionId: String)

    /** Limpeza total — apenas para testes ou reset do app. */
    @Query("DELETE FROM route_points")
    suspend fun deleteAll()
}
