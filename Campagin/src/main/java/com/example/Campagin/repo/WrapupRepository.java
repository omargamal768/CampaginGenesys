package com.example.Campagin.repo;

import com.example.Campagin.model.Wrapup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface WrapupRepository extends JpaRepository<Wrapup, String> {


}