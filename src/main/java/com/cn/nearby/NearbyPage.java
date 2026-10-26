package com.cn.nearby;

import java.util.List;

public record NearbyPage(List<Place> items, int page, int pageSize, boolean hasMore) {
    public record Place(String poiId, String name, String typeName, String typeCode,
                        String address, double longitude, double latitude, Double distanceMeters,
                        Double rating, String photoUrl) {}
}
