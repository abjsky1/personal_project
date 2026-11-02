package backend.analysis.model.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import backend.analysis.model.entity.FoodAnalysis;

public interface FoodAnalysisRepository extends JpaRepository<FoodAnalysis, Long> {
}
