/**
 * Pure-math probability primitives for the System Interface.
 *
 * <p>Every formula here is closed-form and side-effect free, so the whole
 * package is trivially unit-testable. No RuneLite API, no I/O, no logging.
 *
 * <p>Owns the math from the handover:
 * <pre>
 *     P(at least one drop) = 1 - (1 - p)^n
 * </pre>
 * plus expected-trials, still-dry probability, and the binomial percentile
 * rank used by the Luck Analysis System.
 */
package com.systeminterface.common.probability;

