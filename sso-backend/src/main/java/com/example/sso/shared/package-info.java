/**
 * Cross-cutting utilities shared by all modules (common error types, small value projections).
 * Declared OPEN so every module may use these directly without ceremony — this is a supporting
 * module with no domain logic of its own.
 */
@ApplicationModule(type = ApplicationModule.Type.OPEN)
package com.example.sso.shared;

import org.springframework.modulith.ApplicationModule;
