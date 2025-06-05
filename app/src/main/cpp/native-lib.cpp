#include <jni.h>
#include <android/log.h>
#include <vulkan/vulkan.h>
#include <vector>
#include <unordered_map>
#include <string>
#include <queue>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "VulkanApp", __VA_ARGS__)

// Global Vulkan state
struct VulkanState {
    VkInstance instance = VK_NULL_HANDLE;
    VkPhysicalDevice physicalDevice = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    VkQueue queue = VK_NULL_HANDLE;
    VkCommandPool commandPool = VK_NULL_HANDLE;
    VkCommandBuffer commandBuffer = VK_NULL_HANDLE;
    uint32_t queueFamilyIndex = 0;
    bool initialized = false;
    bool commandBufferBegun = false;
};

// Buffer storage
struct BufferStorage {
    VkBuffer buffer = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    VkDeviceSize size;
};

struct CachedPipeline {
    VkShaderModule shaderModule;
    VkDescriptorSetLayout descriptorSetLayout;
    VkPipelineLayout pipelineLayout;
    VkPipeline pipeline;
    uint32_t bufferCount;
};
std::unordered_map<std::string, CachedPipeline> pipelineCache;

// Structure to track pending operation resources
struct PendingOperation {
    VkPipeline pipeline = VK_NULL_HANDLE;
    VkPipelineLayout pipelineLayout = VK_NULL_HANDLE;
    VkDescriptorSet descriptorSet = VK_NULL_HANDLE;
    VkDescriptorPool descriptorPool = VK_NULL_HANDLE;
    VkDescriptorSetLayout descriptorSetLayout = VK_NULL_HANDLE;
    VkShaderModule shaderModule = VK_NULL_HANDLE;
};

static VulkanState vkState;
static std::unordered_map<std::string, BufferStorage> buffers;
static std::queue<PendingOperation> pendingOperations;

void initializeVulkanIfNeeded() {
    if (vkState.initialized) return;

    // Instance
    VkApplicationInfo appInfo = { VK_STRUCTURE_TYPE_APPLICATION_INFO };
    appInfo.pApplicationName = "VulkanBufferManager";
    appInfo.apiVersion = VK_API_VERSION_1_0;

    VkInstanceCreateInfo createInfo = { VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO };
    createInfo.pApplicationInfo = &appInfo;
    vkCreateInstance(&createInfo, nullptr, &vkState.instance);

    // Physical device
    uint32_t deviceCount = 0;
    vkEnumeratePhysicalDevices(vkState.instance, &deviceCount, nullptr);
    std::vector<VkPhysicalDevice> physicalDevices(deviceCount);
    vkEnumeratePhysicalDevices(vkState.instance, &deviceCount, physicalDevices.data());
    vkState.physicalDevice = physicalDevices[0];

    // Queue family
    uint32_t queueFamilyCount = 0;
    vkGetPhysicalDeviceQueueFamilyProperties(vkState.physicalDevice, &queueFamilyCount, nullptr);
    std::vector<VkQueueFamilyProperties> queueFamilies(queueFamilyCount);
    vkGetPhysicalDeviceQueueFamilyProperties(vkState.physicalDevice, &queueFamilyCount, queueFamilies.data());

    for (uint32_t i = 0; i < queueFamilies.size(); ++i) {
        if (queueFamilies[i].queueFlags & VK_QUEUE_COMPUTE_BIT) {
            vkState.queueFamilyIndex = i;
            break;
        }
    }

    // Device
    float priority = 1.0f;
    VkDeviceQueueCreateInfo queueCreateInfo = { VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO };
    queueCreateInfo.queueFamilyIndex = vkState.queueFamilyIndex;
    queueCreateInfo.queueCount = 1;
    queueCreateInfo.pQueuePriorities = &priority;

    VkDeviceCreateInfo deviceCreateInfo = { VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO };
    deviceCreateInfo.queueCreateInfoCount = 1;
    deviceCreateInfo.pQueueCreateInfos = &queueCreateInfo;
    vkCreateDevice(vkState.physicalDevice, &deviceCreateInfo, nullptr, &vkState.device);

    // Get queue
    vkGetDeviceQueue(vkState.device, vkState.queueFamilyIndex, 0, &vkState.queue);

    // Create command pool
    VkCommandPoolCreateInfo cmdPoolInfo = { VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO };
    cmdPoolInfo.queueFamilyIndex = vkState.queueFamilyIndex;
    cmdPoolInfo.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    vkCreateCommandPool(vkState.device, &cmdPoolInfo, nullptr, &vkState.commandPool);

    // Allocate command buffer
    VkCommandBufferAllocateInfo cmdAllocInfo = { VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO };
    cmdAllocInfo.commandPool = vkState.commandPool;
    cmdAllocInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    cmdAllocInfo.commandBufferCount = 1;
    vkAllocateCommandBuffers(vkState.device, &cmdAllocInfo, &vkState.commandBuffer);

    vkState.initialized = true;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_example_tinygrad_1remote_1android_MainActivity_createBuffer(
        JNIEnv* env,
        jobject /* this */,
        jstring key,
        jint sizeInBytes) {
    initializeVulkanIfNeeded();

    const char* keyChars = env->GetStringUTFChars(key, nullptr);
    std::string bufferKey(keyChars);
    env->ReleaseStringUTFChars(key, keyChars);

    BufferStorage newBuffer;
    newBuffer.size = sizeInBytes;

    // Create buffer
    VkBufferCreateInfo bufferInfo = { VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO };
    bufferInfo.size = sizeInBytes;
    bufferInfo.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
    bufferInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    vkCreateBuffer(vkState.device, &bufferInfo, nullptr, &newBuffer.buffer);

    // Allocate memory
    VkMemoryRequirements memRequirements;
    vkGetBufferMemoryRequirements(vkState.device, newBuffer.buffer, &memRequirements);

    VkPhysicalDeviceMemoryProperties memProperties;
    vkGetPhysicalDeviceMemoryProperties(vkState.physicalDevice, &memProperties);

    uint32_t memoryTypeIndex = 0;
    for (uint32_t i = 0; i < memProperties.memoryTypeCount; i++) {
        if ((memRequirements.memoryTypeBits & (1 << i)) &&
            (memProperties.memoryTypes[i].propertyFlags & VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT)) {
            memoryTypeIndex = i;
            break;
        }
    }

    VkMemoryAllocateInfo allocInfo = { VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO };
    allocInfo.allocationSize = memRequirements.size;
    allocInfo.memoryTypeIndex = memoryTypeIndex;
    vkAllocateMemory(vkState.device, &allocInfo, nullptr, &newBuffer.memory);

    // Bind memory
    vkBindBufferMemory(vkState.device, newBuffer.buffer, newBuffer.memory, 0);

    // Store using key
    buffers[bufferKey] = newBuffer;
}

extern "C"
JNIEXPORT jbyteArray JNICALL
Java_com_example_tinygrad_1remote_1android_MainActivity_getBuffer(
        JNIEnv *env,
        jobject /* this */,
        jstring key) {
    const char* keyChars = env->GetStringUTFChars(key, nullptr);
    std::string bufferKey(keyChars);
    env->ReleaseStringUTFChars(key, keyChars);

    if (buffers.find(bufferKey) == buffers.end()) {
        LOGI("Error: Buffer with key '%s' not found", bufferKey.c_str());
        return env->NewByteArray(0);
    }

    // If command buffer was used, submit and wait
    if (vkState.commandBufferBegun) {
        vkEndCommandBuffer(vkState.commandBuffer);

        // Create fence
        VkFenceCreateInfo fenceInfo = { VK_STRUCTURE_TYPE_FENCE_CREATE_INFO };
        VkFence fence;
        vkCreateFence(vkState.device, &fenceInfo, nullptr, &fence);

        // Submit command buffer
        VkSubmitInfo submitInfo = { VK_STRUCTURE_TYPE_SUBMIT_INFO };
        submitInfo.commandBufferCount = 1;
        submitInfo.pCommandBuffers = &vkState.commandBuffer;
        vkQueueSubmit(vkState.queue, 1, &submitInfo, fence);

        // Wait for completion
        vkWaitForFences(vkState.device, 1, &fence, VK_TRUE, UINT64_MAX);
        vkDestroyFence(vkState.device, fence, nullptr);

        // Reset command buffer
        vkResetCommandBuffer(vkState.commandBuffer, 0);
        vkState.commandBufferBegun = false;
    }

    // Clean up pending operation resources
    while (!pendingOperations.empty()) {
        auto& op = pendingOperations.front();
        vkDestroyPipeline(vkState.device, op.pipeline, nullptr);
        vkDestroyDescriptorPool(vkState.device, op.descriptorPool, nullptr);
        vkDestroyPipelineLayout(vkState.device, op.pipelineLayout, nullptr);
        vkDestroyDescriptorSetLayout(vkState.device, op.descriptorSetLayout, nullptr);
        vkDestroyShaderModule(vkState.device, op.shaderModule, nullptr);
        pendingOperations.pop();
    }

    BufferStorage& buf = buffers[bufferKey];
    VkDeviceSize bufferSize = buf.size;

    void* data;
    vkMapMemory(vkState.device, buf.memory, 0, bufferSize, 0, &data);

    jbyteArray result = env->NewByteArray(static_cast<jsize>(bufferSize));
    env->SetByteArrayRegion(result, 0, static_cast<jsize>(bufferSize), reinterpret_cast<jbyte*>(data));

    vkUnmapMemory(vkState.device, buf.memory);
    return result;
}

extern "C"
JNIEXPORT float JNICALL
Java_com_example_tinygrad_1remote_1android_MainActivity_runVulkanCompute(
        JNIEnv *env,
        jobject /* this */,
        jbyteArray shaderBytes,
        jintArray dispatchSizeArray,
        jintArray bufferIndexesArray,
        jstring name) {

    initializeVulkanIfNeeded();

    // --- Extract inputs ---
    const char* nameStr = env->GetStringUTFChars(name, nullptr);
    std::string nameKey(nameStr);
    env->ReleaseStringUTFChars(name, nameStr);

    jint* bufferIndexes = env->GetIntArrayElements(bufferIndexesArray, nullptr);
    jsize bufferCount = env->GetArrayLength(bufferIndexesArray);

    if (buffers.empty() || bufferCount == 0) {
        LOGI("Error: No buffers created or no buffer indexes given!");
        env->ReleaseIntArrayElements(bufferIndexesArray, bufferIndexes, JNI_ABORT);
        return 0.0f;
    }

    // --- Pipeline Cache ---
    struct CachedPipeline {
        VkShaderModule shaderModule;
        VkDescriptorSetLayout descriptorSetLayout;
        VkPipelineLayout pipelineLayout;
        VkPipeline pipeline;
        uint32_t bufferCount;
    };
    static std::unordered_map<std::string, CachedPipeline> pipelineCache;

    CachedPipeline* cached = nullptr;
    if (pipelineCache.find(nameKey) != pipelineCache.end()) {
        CachedPipeline& candidate = pipelineCache[nameKey];
        if (candidate.bufferCount == static_cast<uint32_t>(bufferCount)) {
            cached = &candidate;
        }
    }

    // --- Build Pipeline if Not Cached ---
    if (!cached) {
        CachedPipeline newPipeline;
        newPipeline.bufferCount = static_cast<uint32_t>(bufferCount);

        // Shader module
        jbyte* shaderData = env->GetByteArrayElements(shaderBytes, nullptr);
        jsize shaderSize = env->GetArrayLength(shaderBytes);

        VkShaderModuleCreateInfo shaderModuleCreateInfo = { VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO };
        shaderModuleCreateInfo.codeSize = static_cast<size_t>(shaderSize);
        shaderModuleCreateInfo.pCode = reinterpret_cast<const uint32_t*>(shaderData);
        vkCreateShaderModule(vkState.device, &shaderModuleCreateInfo, nullptr, &newPipeline.shaderModule);
        env->ReleaseByteArrayElements(shaderBytes, shaderData, JNI_ABORT);

        // Descriptor set layout
        std::vector<VkDescriptorSetLayoutBinding> layoutBindings(bufferCount);
        for (jsize i = 0; i < bufferCount; ++i) {
            layoutBindings[i] = {};
            layoutBindings[i].binding = static_cast<uint32_t>(i);
            layoutBindings[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
            layoutBindings[i].descriptorCount = 1;
            layoutBindings[i].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
        }

        VkDescriptorSetLayoutCreateInfo descriptorLayoutInfo = { VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO };
        descriptorLayoutInfo.bindingCount = static_cast<uint32_t>(bufferCount);
        descriptorLayoutInfo.pBindings = layoutBindings.data();
        vkCreateDescriptorSetLayout(vkState.device, &descriptorLayoutInfo, nullptr, &newPipeline.descriptorSetLayout);

        // Pipeline layout
        VkPipelineLayoutCreateInfo layoutInfo = { VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO };
        layoutInfo.setLayoutCount = 1;
        layoutInfo.pSetLayouts = &newPipeline.descriptorSetLayout;
        vkCreatePipelineLayout(vkState.device, &layoutInfo, nullptr, &newPipeline.pipelineLayout);

        // Compute pipeline
        VkPipelineShaderStageCreateInfo shaderStageInfo = { VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO };
        shaderStageInfo.stage = VK_SHADER_STAGE_COMPUTE_BIT;
        shaderStageInfo.module = newPipeline.shaderModule;
        shaderStageInfo.pName = "main";

        VkComputePipelineCreateInfo pipelineInfo = { VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO };
        pipelineInfo.stage = shaderStageInfo;
        pipelineInfo.layout = newPipeline.pipelineLayout;
        vkCreateComputePipelines(vkState.device, VK_NULL_HANDLE, 1, &pipelineInfo, nullptr, &newPipeline.pipeline);

        pipelineCache[nameKey] = newPipeline;
        cached = &pipelineCache[nameKey];
    }

    // --- Descriptor pool ---
    VkDescriptorPool descriptorPool;
    VkDescriptorPoolSize poolSize = { VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, static_cast<uint32_t>(bufferCount) };
    VkDescriptorPoolCreateInfo poolInfo = { VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO };
    poolInfo.poolSizeCount = 1;
    poolInfo.pPoolSizes = &poolSize;
    poolInfo.maxSets = 1;
    vkCreateDescriptorPool(vkState.device, &poolInfo, nullptr, &descriptorPool);

    // --- Allocate descriptor set ---
    VkDescriptorSet descriptorSet;
    VkDescriptorSetAllocateInfo allocInfo = { VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO };
    allocInfo.descriptorPool = descriptorPool;
    allocInfo.descriptorSetCount = 1;
    allocInfo.pSetLayouts = &cached->descriptorSetLayout;
    vkAllocateDescriptorSets(vkState.device, &allocInfo, &descriptorSet);

    // --- Bind buffers to descriptor ---
    std::vector<VkWriteDescriptorSet> descriptorWrites(bufferCount);
    std::vector<VkDescriptorBufferInfo> bufferInfos(bufferCount);

    for (jsize i = 0; i < bufferCount; ++i) {
        std::string key = std::to_string(bufferIndexes[i]);
        if (buffers.find(key) == buffers.end()) {
            LOGI("Error: Buffer index %s not found!", key.c_str());
            vkDestroyDescriptorPool(vkState.device, descriptorPool, nullptr);
            env->ReleaseIntArrayElements(bufferIndexesArray, bufferIndexes, JNI_ABORT);
            return 0.0f;
        }

        VkBuffer buf = buffers[key].buffer;

        VkMemoryRequirements memRequirements;
        vkGetBufferMemoryRequirements(vkState.device, buf, &memRequirements);

        bufferInfos[i].buffer = buf;
        bufferInfos[i].offset = 0;
        bufferInfos[i].range = memRequirements.size;

        descriptorWrites[i] = { VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET };
        descriptorWrites[i].dstSet = descriptorSet;
        descriptorWrites[i].dstBinding = static_cast<uint32_t>(i);
        descriptorWrites[i].descriptorCount = 1;
        descriptorWrites[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        descriptorWrites[i].pBufferInfo = &bufferInfos[i];
    }

    vkUpdateDescriptorSets(vkState.device, static_cast<uint32_t>(bufferCount), descriptorWrites.data(), 0, nullptr);
    env->ReleaseIntArrayElements(bufferIndexesArray, bufferIndexes, JNI_ABORT);

    // --- Begin command buffer if needed ---
    if (!vkState.commandBufferBegun) {
        VkCommandBufferBeginInfo beginInfo = { VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO };
        vkBeginCommandBuffer(vkState.commandBuffer, &beginInfo);
        vkState.commandBufferBegun = true;
    }

    vkCmdBindPipeline(vkState.commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, cached->pipeline);
    vkCmdBindDescriptorSets(vkState.commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, cached->pipelineLayout, 0, 1, &descriptorSet, 0, nullptr);

    // --- Dispatch ---
    jint* dispatchSize = env->GetIntArrayElements(dispatchSizeArray, nullptr);
    uint32_t groupCountX = static_cast<uint32_t>(dispatchSize[0]);
    uint32_t groupCountY = static_cast<uint32_t>(dispatchSize[1]);
    uint32_t groupCountZ = static_cast<uint32_t>(dispatchSize[2]);
    env->ReleaseIntArrayElements(dispatchSizeArray, dispatchSize, JNI_ABORT);

    vkCmdDispatch(vkState.commandBuffer, groupCountX, groupCountY, groupCountZ);

    // --- Store for cleanup ---
    PendingOperation op;
    op.pipeline = VK_NULL_HANDLE;
    op.pipelineLayout = VK_NULL_HANDLE;
    op.descriptorSet = descriptorSet;
    op.descriptorPool = descriptorPool;
    op.descriptorSetLayout = VK_NULL_HANDLE;
    op.shaderModule = VK_NULL_HANDLE;
    pendingOperations.push(op);

    return 0.0f;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_example_tinygrad_1remote_1android_MainActivity_uploadBuffer(
        JNIEnv *env,
        jobject /* this */,
        jint bufferIndex,
        jbyteArray dataArray) {
    initializeVulkanIfNeeded();

    auto it = buffers.find(std::to_string(bufferIndex));
    if (it == buffers.end()) {
        LOGI("Error: Buffer index %d not found", bufferIndex);
        return;
    }

    VkBuffer buffer = it->second.buffer;
    VkDeviceMemory bufferMemory = it->second.memory;

    jbyte* data = env->GetByteArrayElements(dataArray, nullptr);
    jsize length = env->GetArrayLength(dataArray);

    void* mappedMemory = nullptr;
    vkMapMemory(vkState.device, bufferMemory, 0, length, 0, &mappedMemory);
    memcpy(mappedMemory, data, length);
    vkUnmapMemory(vkState.device, bufferMemory);

    env->ReleaseByteArrayElements(dataArray, data, JNI_ABORT);
    LOGI("uploadBuffer: Copied %d bytes to buffer %d", length, bufferIndex);
}