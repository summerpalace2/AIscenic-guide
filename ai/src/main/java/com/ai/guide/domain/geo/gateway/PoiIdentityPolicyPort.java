package com.ai.guide.domain.geo.gateway;

/** Runtime policy for deciding whether a provider POI is a credible identity match. */
public interface PoiIdentityPolicyPort {
    record Policy(int exactNameScore, int partialNameScore, int coordinateBonus,
                  int poiIdBonus, int minimumAcceptableScore) {
        public static Policy unconfigured() { return new Policy(0, 0, 0, 0, Integer.MAX_VALUE); }
        public boolean configured() {
            return exactNameScore > partialNameScore && partialNameScore > 0
                    && coordinateBonus >= 0 && poiIdBonus >= 0 && minimumAcceptableScore > 0;
        }
    }
    Policy getPoiIdentityPolicy();
}
