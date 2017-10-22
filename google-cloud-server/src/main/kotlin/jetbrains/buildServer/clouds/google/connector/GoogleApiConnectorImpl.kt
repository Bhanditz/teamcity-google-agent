package jetbrains.buildServer.clouds.google.connector

import com.google.api.client.googleapis.util.Utils
import com.google.api.client.json.GenericJson
import com.google.auth.oauth2.GoogleCredentials
import com.google.cloud.compute.*
import com.google.cloud.compute.NetworkId
import com.google.cloud.resourcemanager.ResourceManagerOptions
import jetbrains.buildServer.clouds.CloudException
import jetbrains.buildServer.clouds.CloudInstanceUserData
import jetbrains.buildServer.clouds.base.connector.AbstractInstance
import jetbrains.buildServer.clouds.base.errors.TypedCloudErrorInfo
import jetbrains.buildServer.clouds.google.GoogleCloudImage
import jetbrains.buildServer.clouds.google.GoogleCloudInstance
import jetbrains.buildServer.clouds.google.GoogleConstants
import jetbrains.buildServer.clouds.google.utils.AlphaNumericStringComparator
import kotlinx.coroutines.experimental.CommonPool
import kotlinx.coroutines.experimental.CoroutineStart
import kotlinx.coroutines.experimental.async


class GoogleApiConnectorImpl(private val accessKey: String) : GoogleApiConnector {

    private val comparator = AlphaNumericStringComparator()
    private val compute: Compute
    private var myServerId: String? = null
    private var myProfileId: String? = null
    private var myProjectId: String? = null

    init {
        val builder = ComputeOptions.newBuilder()
        accessKey.trim().byteInputStream().use {
            val factory = Utils.getDefaultJsonFactory()
            val parser = factory.createJsonParser(it)
            val json = parser.parse(GenericJson::class.java)
            json["project_id"]?.let {
                builder.setProjectId(it as String)
                myProjectId = it
            }

            it.reset()
            builder.setCredentials(GoogleCredentials.fromStream(it))
        }

        compute = builder.build().service
    }

    override fun test() {
        val builder = ResourceManagerOptions.newBuilder()
        accessKey.trim().byteInputStream().use {
            val credentials = GoogleCredentials.fromStream(it)
            builder.setCredentials(credentials)
        }

        val resourceManager = builder.build().service
        val missingPermissions = mutableListOf<String>()
        resourceManager.testPermissions(myProjectId, REQUIRED_PERMISSIONS).forEachIndexed { i, exists ->
            if (!exists) missingPermissions.add(REQUIRED_PERMISSIONS[i])
        }

        if (missingPermissions.isNotEmpty()) {
            throw CloudException(missingPermissions.joinToString(", ", "Missing required permissions: "))
        }
    }

    override fun createVmAsync(instance: GoogleCloudInstance, userData: CloudInstanceUserData) = async(CommonPool, CoroutineStart.LAZY) {
        val details = instance.image.imageDetails
        val zone = details.zone
        val machineType = details.machineType
        val network = details.network ?: "default"

        val imageId = ImageId.of(myProjectId, details.sourceImage)
        val networkId = NetworkId.of(network)
        val attachedDisk = AttachedDisk.of(AttachedDisk.CreateDiskConfiguration
                .newBuilder(imageId)
                .setAutoDelete(true)
                .build())
        val networkInterface = NetworkInterface.newBuilder(networkId)
                .setAccessConfigurations(listOf(NetworkInterface.AccessConfig.of()))
                .build()
        val instanceId = InstanceId.of(zone, instance.instanceId)
        val machineTypeId = MachineTypeId.of(zone, machineType)
        val instanceInfo = InstanceInfo.newBuilder(instanceId, machineTypeId)
                .setAttachedDisks(listOf(attachedDisk))
                .setNetworkInterfaces(listOf(networkInterface))
                .setMetadata(Metadata.of(mapOf(
                        GoogleConstants.TAG_SERVER to myServerId,
                        GoogleConstants.TAG_DATA to userData.serialize(),
                        GoogleConstants.TAG_PROFILE to myProfileId,
                        GoogleConstants.TAG_SOURCE to details.sourceId
                )))
                .apply {
                    if (details.preemptible) {
                        setSchedulingOptions(SchedulingOptions.preemptible())
                    }
                }
                .build()

        var operation = compute.create(instanceInfo)
        operation = operation.waitFor()
        operation.errors?.let {
            throw CloudException(it.joinToString())
        }
    }

    override fun startVmAsync(instance: GoogleCloudInstance) = async(CommonPool, CoroutineStart.LAZY) {
        getInstance(instance)?.start()
    }

    override fun restartVmAsync(instance: GoogleCloudInstance) = async(CommonPool, CoroutineStart.LAZY) {
        getInstance(instance)?.reset()
    }

    override fun stopVmAsync(instance: GoogleCloudInstance) = async(CommonPool, CoroutineStart.LAZY) {
        getInstance(instance)?.stop()
    }

    override fun deleteVmAsync(instance: GoogleCloudInstance) = async(CommonPool, CoroutineStart.LAZY) {
        getInstance(instance)?.delete()
    }

    private fun getInstance(instance: GoogleCloudInstance): Instance? {
        val instanceId = InstanceId.of(instance.zone, instance.id)
        return compute.getInstance(instanceId)
    }

    override fun checkImage(image: GoogleCloudImage): Array<TypedCloudErrorInfo> {
        return emptyArray()
    }

    override fun checkInstance(instance: GoogleCloudInstance): Array<TypedCloudErrorInfo> {
        return emptyArray()
    }

    override fun getImagesAsync() = async(CommonPool, CoroutineStart.LAZY) {
        compute.listImages().iterateAll()
                .map { it.imageId.image to (it.description ?: it.imageId.image) }
                .sortedWith(compareBy(comparator, { it -> it.second }))
                .associate { it -> it.first to it.second }
    }

    override fun getZonesAsync() = async(CommonPool, CoroutineStart.LAZY) {
        compute.listZones().iterateAll()
                .map { it -> it.zoneId.zone to (it.description ?: it.zoneId.zone) }
                .sortedWith(compareBy(comparator, { it -> it.second }))
                .associate { it -> it.first to it.second }
    }

    override fun getMachineTypesAsync() = async(CommonPool, CoroutineStart.LAZY) {
        compute.listMachineTypes().iterateAll()
                .map { it -> it.machineTypeId.type to (it.description ?: it.machineTypeId.type) }
                .sortedWith(compareBy(comparator, { it -> it.second }))
                .associate { it -> it.first to it.second }
    }

    override fun getNetworksAsync() = async(CommonPool, CoroutineStart.LAZY) {
        compute.listNetworks().iterateAll()
                .map { it -> it.networkId.network to (it.description ?: it.networkId.network) }
                .sortedWith(compareBy(comparator, { it -> it.second }))
                .associate { it -> it.first to it.second }
    }

    override fun <R : AbstractInstance?> fetchInstances(image: GoogleCloudImage): MutableMap<String, R> {
        val instances = fetchInstances<R>(arrayListOf(image))
        return instances[image] as MutableMap<String, R>
    }

    override fun <R : AbstractInstance?> fetchInstances(images: MutableCollection<GoogleCloudImage>): MutableMap<GoogleCloudImage, MutableMap<String, R>> {
        val map = compute.listInstances().iterateAll().filter { it ->
            val values = it.metadata.values
            if (values[GoogleConstants.TAG_SERVER] != myServerId) return@filter false
            if (values[GoogleConstants.TAG_PROFILE] != myProfileId) return@filter false
            if (values[GoogleConstants.TAG_DATA].isNullOrEmpty()) return@filter false
            !values[GoogleConstants.TAG_SOURCE].isNullOrEmpty()
        }.groupBy { it.metadata.values[GoogleConstants.TAG_SOURCE] }
        val result = hashMapOf<GoogleCloudImage, MutableMap<String, R>>()
        for (image in images) {
            val instances = hashMapOf<String, R>()
            map[image.imageDetails.sourceId]?.let {
                it.forEach {
                    @Suppress("UNCHECKED_CAST")
                    instances[it.instanceId.instance] = GoogleInstance(it) as R
                }
            }
            result[image] = instances
        }
        return result
    }

    fun setServerId(serverId: String?) {
        myServerId = serverId
    }

    fun setProfileId(profileId: String) {
        myProfileId = profileId
    }

    companion object {
        val REQUIRED_PERMISSIONS = listOf(
                "compute.images.list",
                "compute.instances.create",
                "compute.instances.list",
                "compute.instances.setMetadata",
                "compute.machineTypes.list",
                "compute.networks.list",
                "compute.zones.list")
    }
}