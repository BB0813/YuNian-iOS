package com.lianyu.ai.database.repository

import com.lianyu.ai.database.dao.CompanionDao
import com.lianyu.ai.database.model.CompanionEntity
import kotlinx.coroutines.flow.Flow

class CompanionRepository(private val companionDao: CompanionDao) {
    fun getAllCompanions(): Flow<List<CompanionEntity>> = companionDao.getAllCompanions()

    suspend fun getCompanionById(id: Long): CompanionEntity? = companionDao.getCompanionById(id)

    fun getCompanionByIdFlow(id: Long): Flow<CompanionEntity?> = companionDao.getCompanionByIdFlow(id)

    suspend fun insertCompanion(companion: CompanionEntity): Long = companionDao.insertCompanion(companion)

    suspend fun updateCompanion(companion: CompanionEntity) = companionDao.updateCompanion(companion)

    suspend fun deleteCompanion(companion: CompanionEntity) = companionDao.deleteCompanion(companion)

    suspend fun updateTimestamp(id: Long) = companionDao.updateTimestamp(id)

    suspend fun increaseIntimacy(id: Long, amount: Int = 1) = companionDao.increaseIntimacy(id, amount)

    suspend fun getIntimacy(id: Long): Int? = companionDao.getIntimacy(id)
}
