package com.lx.governance.profile.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lx.governance.profile.dto.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RedisProfileServiceTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private UserProfileService userProfileService;

    @Mock
    private HashOperations<String, Object, Object> hashOperations;

    @Mock
    private ValueOperations<String, Object> valueOperations;

    @InjectMocks
    private RedisProfileService redisProfileService;

    private UserProfileDTO mockProfile;
    private String customerId;
    private String redisKey;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws JsonProcessingException {
        customerId = "CUST001";
        redisKey = "ai:profile:" + customerId;
        objectMapper = new ObjectMapper();

        // Build mock profile
        mockProfile = buildMockProfile();
    }

    private UserProfileDTO buildMockProfile() throws JsonProcessingException {
        BasicInfoDTO basicInfo = BasicInfoDTO.builder()
                .customerId(customerId)
                .name("张三")
                .age(35)
                .gender("男")
                .phone("13800138000")
                .email("zhangsan@example.com")
                .build();

        List<String> healthGoals = Arrays.asList("减重5kg", "改善睡眠");
        HealthProfileDTO healthProfile = HealthProfileDTO.builder()
                .bmi(24.5)
                .bloodPressure("120/80")
                .bloodSugar(5.6)
                .latestAssessmentDate(LocalDate.of(2026, 5, 1))
                .healthGoals(healthGoals)
                .build();

        BehaviorPreferencesDTO behaviorPreferences = BehaviorPreferencesDTO.builder()
                .dietPreference("低碳水")
                .exerciseFrequency("每周3次")
                .checkInStreak(15)
                .build();

        GlobalMemoryDTO memory1 = GlobalMemoryDTO.builder()
                .type("health_goal")
                .key("weight_loss")
                .content("用户希望在3个月内减重5kg")
                .verified(true)
                .build();

        GlobalMemoryDTO memory2 = GlobalMemoryDTO.builder()
                .type("preference")
                .key("exercise_time")
                .content("用户偏好晚上7点运动")
                .verified(true)
                .build();

        return UserProfileDTO.builder()
                .basicInfo(basicInfo)
                .healthProfile(healthProfile)
                .behaviorPreferences(behaviorPreferences)
                .globalMemories(Arrays.asList(memory1, memory2))
                .build();
    }

    private Map<Object, Object> buildCachedData() throws JsonProcessingException {
        Map<Object, Object> cached = new HashMap<>();

        // Basic info
        cached.put("basic:customerId", customerId);
        cached.put("basic:name", "张三");
        cached.put("basic:age", 35);
        cached.put("basic:gender", "男");
        cached.put("basic:phone", "13800138000");
        cached.put("basic:email", "zhangsan@example.com");

        // Health profile
        cached.put("health:bmi", 24.5);
        cached.put("health:bloodPressure", "120/80");
        cached.put("health:bloodSugar", 5.6);
        cached.put("health:latestAssessmentDate", "2026-05-01");
        cached.put("health:healthGoals", objectMapper.writeValueAsString(Arrays.asList("减重5kg", "改善睡眠")));

        // Behavior preferences
        cached.put("behavior:dietPreference", "低碳水");
        cached.put("behavior:exerciseFrequency", "每周3次");
        cached.put("behavior:checkInStreak", 15);

        // Global memories
        List<GlobalMemoryDTO> memories = mockProfile.getGlobalMemories();
        cached.put("memories", objectMapper.writeValueAsString(memories));

        // Metadata
        cached.put("meta:cachedAt", System.currentTimeMillis());
        cached.put("meta:version", "v1");

        return cached;
    }

    @Test
    void testGetProfile_CacheHit() throws JsonProcessingException {
        // Given: Redis has cached data
        Map<Object, Object> cachedData = buildCachedData();
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.entries(redisKey)).thenReturn(cachedData);

        // When: Call getProfile
        UserProfileDTO result = redisProfileService.getProfile(customerId);

        // Then: Return cached data without calling buildUserProfile
        assertNotNull(result);
        assertEquals(customerId, result.getBasicInfo().getCustomerId());
        assertEquals("张三", result.getBasicInfo().getName());
        assertEquals(35, result.getBasicInfo().getAge());
        assertEquals("男", result.getBasicInfo().getGender());

        assertEquals(24.5, result.getHealthProfile().getBmi());
        assertEquals("120/80", result.getHealthProfile().getBloodPressure());
        assertEquals(5.6, result.getHealthProfile().getBloodSugar());
        assertEquals(LocalDate.of(2026, 5, 1), result.getHealthProfile().getLatestAssessmentDate());
        assertEquals(2, result.getHealthProfile().getHealthGoals().size());

        assertEquals("低碳水", result.getBehaviorPreferences().getDietPreference());
        assertEquals("每周3次", result.getBehaviorPreferences().getExerciseFrequency());
        assertEquals(15, result.getBehaviorPreferences().getCheckInStreak());

        assertEquals(2, result.getGlobalMemories().size());
        assertEquals("health_goal", result.getGlobalMemories().get(0).getType());
        assertEquals("weight_loss", result.getGlobalMemories().get(0).getKey());

        // Verify buildUserProfile was NOT called
        verify(userProfileService, never()).buildUserProfile(anyString());
        verify(hashOperations, times(1)).entries(redisKey);
    }

    @Test
    void testGetProfile_CacheMiss_AndWriteBack() {
        // Given: Redis has no cached data
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.entries(redisKey)).thenReturn(Collections.emptyMap());
        when(redisTemplate.hasKey(redisKey + ":null")).thenReturn(false);
        when(userProfileService.buildUserProfile(customerId)).thenReturn(mockProfile);

        // When: Call getProfile
        UserProfileDTO result = redisProfileService.getProfile(customerId);

        // Then: Call buildUserProfile and write to Redis
        assertNotNull(result);
        assertEquals(customerId, result.getBasicInfo().getCustomerId());
        assertEquals("张三", result.getBasicInfo().getName());

        // Verify buildUserProfile was called
        verify(userProfileService, times(1)).buildUserProfile(customerId);

        // Verify data was written to Redis
        ArgumentCaptor<Map<String, Object>> mapCaptor = ArgumentCaptor.forClass(Map.class);
        verify(hashOperations, times(1)).putAll(eq(redisKey), mapCaptor.capture());

        Map<String, Object> savedData = mapCaptor.getValue();
        assertTrue(savedData.containsKey("basic:customerId"));
        assertTrue(savedData.containsKey("basic:name"));
        assertTrue(savedData.containsKey("health:bmi"));
        assertTrue(savedData.containsKey("behavior:dietPreference"));
        assertTrue(savedData.containsKey("memories"));
        assertTrue(savedData.containsKey("meta:cachedAt"));
        assertTrue(savedData.containsKey("meta:version"));

        // Verify TTL was set
        verify(redisTemplate, times(1)).expire(redisKey, 24, TimeUnit.HOURS);
    }

    @Test
    void testGetProfile_CacheMiss_NullResult_CachePenetrationProtection() {
        // Given: Redis has no cached data and buildUserProfile returns null
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.entries(redisKey)).thenReturn(Collections.emptyMap());
        when(redisTemplate.hasKey(redisKey + ":null")).thenReturn(false);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(userProfileService.buildUserProfile(customerId)).thenReturn(null);

        // When: Call getProfile
        UserProfileDTO result = redisProfileService.getProfile(customerId);

        // Then: Return null and cache null marker
        assertNull(result);

        // Verify null marker was cached with 5 minutes TTL
        verify(valueOperations, times(1)).set(redisKey + ":null", "1", 5, TimeUnit.MINUTES);

        // Verify no hash data was written
        verify(hashOperations, never()).putAll(anyString(), anyMap());
    }

    @Test
    void testGetProfile_NullMarkerHit() {
        // Given: Null marker exists in Redis
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.entries(redisKey)).thenReturn(Collections.emptyMap());
        when(redisTemplate.hasKey(redisKey + ":null")).thenReturn(true);

        // When: Call getProfile
        UserProfileDTO result = redisProfileService.getProfile(customerId);

        // Then: Return null immediately without calling buildUserProfile
        assertNull(result);
        verify(userProfileService, never()).buildUserProfile(anyString());
    }

    @Test
    void testGetProfile_RedisException_FallbackToMySQL() {
        // Given: Redis throws exception
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.entries(redisKey)).thenThrow(new RuntimeException("Redis connection failed"));
        when(userProfileService.buildUserProfile(customerId)).thenReturn(mockProfile);

        // When: Call getProfile
        UserProfileDTO result = redisProfileService.getProfile(customerId);

        // Then: Fallback to MySQL and return profile
        assertNotNull(result);
        assertEquals(customerId, result.getBasicInfo().getCustomerId());

        // Verify buildUserProfile was called as fallback
        verify(userProfileService, times(1)).buildUserProfile(customerId);
    }

    @Test
    void testRefreshProfile_Success() {
        // Given: UserProfileService returns profile data
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(userProfileService.buildUserProfile(customerId)).thenReturn(mockProfile);
        when(redisTemplate.delete(redisKey)).thenReturn(true);
        when(redisTemplate.delete(redisKey + ":null")).thenReturn(true);

        // When: Call refreshProfile
        redisProfileService.refreshProfile(customerId);

        // Then: Delete old cache and write new cache
        verify(redisTemplate, times(1)).delete(redisKey);
        verify(redisTemplate, times(1)).delete(redisKey + ":null");

        // Verify new data was written
        ArgumentCaptor<Map<String, Object>> mapCaptor = ArgumentCaptor.forClass(Map.class);
        verify(hashOperations, times(1)).putAll(eq(redisKey), mapCaptor.capture());

        Map<String, Object> savedData = mapCaptor.getValue();
        assertTrue(savedData.containsKey("basic:customerId"));
        assertTrue(savedData.containsKey("basic:name"));

        // Verify TTL was set
        verify(redisTemplate, times(1)).expire(redisKey, 24, TimeUnit.HOURS);
    }

    @Test
    void testRefreshProfile_NullProfile_NoCache() {
        // Given: UserProfileService returns null
        when(userProfileService.buildUserProfile(customerId)).thenReturn(null);

        // When: Call refreshProfile
        redisProfileService.refreshProfile(customerId);

        // Then: No cache operations should be performed (null profile means no data to cache)
        verify(redisTemplate, never()).delete(anyString());
        verify(hashOperations, never()).putAll(anyString(), anyMap());
    }

    @Test
    void testRefreshProfile_Exception_ThrowsRuntimeException() {
        // Given: UserProfileService throws exception
        when(userProfileService.buildUserProfile(customerId))
                .thenThrow(new RuntimeException("Database connection failed"));

        // When & Then: Call refreshProfile should throw RuntimeException
        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            redisProfileService.refreshProfile(customerId);
        });

        assertTrue(exception.getMessage().contains("刷新画像缓存失败"));

        // Verify no cache operations were performed
        verify(redisTemplate, never()).delete(anyString());
        verify(hashOperations, never()).putAll(anyString(), anyMap());
    }

    @Test
    void testGetProfile_DeserializationWithMissingFields() {
        // Given: Redis has partial cached data (missing some fields)
        Map<Object, Object> partialData = new HashMap<>();
        partialData.put("basic:customerId", customerId);
        partialData.put("basic:name", "张三");
        // Missing age, gender, phone, email
        partialData.put("health:bmi", 24.5);
        // Missing other health fields
        partialData.put("meta:cachedAt", System.currentTimeMillis());

        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.entries(redisKey)).thenReturn(partialData);

        // When: Call getProfile
        UserProfileDTO result = redisProfileService.getProfile(customerId);

        // Then: Return profile with available fields, nulls for missing fields
        assertNotNull(result);
        assertEquals(customerId, result.getBasicInfo().getCustomerId());
        assertEquals("张三", result.getBasicInfo().getName());
        assertNull(result.getBasicInfo().getAge());
        assertNull(result.getBasicInfo().getGender());

        assertEquals(24.5, result.getHealthProfile().getBmi());
        assertNull(result.getHealthProfile().getBloodPressure());
    }

    @Test
    void testGetProfile_TypeConversion() {
        // Given: Redis has data with different numeric types
        Map<Object, Object> cachedData = new HashMap<>();
        cachedData.put("basic:customerId", customerId);
        cachedData.put("basic:name", "张三");
        cachedData.put("basic:age", "35"); // String instead of Integer
        cachedData.put("health:bmi", "24.5"); // String instead of Double
        cachedData.put("behavior:checkInStreak", 15L); // Long instead of Integer
        cachedData.put("meta:cachedAt", System.currentTimeMillis());

        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.entries(redisKey)).thenReturn(cachedData);

        // When: Call getProfile
        UserProfileDTO result = redisProfileService.getProfile(customerId);

        // Then: Type conversion should work correctly
        assertNotNull(result);
        assertEquals(35, result.getBasicInfo().getAge());
        assertEquals(24.5, result.getHealthProfile().getBmi());
        assertEquals(15, result.getBehaviorPreferences().getCheckInStreak());
    }
}
