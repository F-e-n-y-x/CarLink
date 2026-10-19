package com.carlink.core.settings

import android.content.Context
import android.content.SharedPreferences

/**
 * The make of the driver's car, as chosen in Settings › Car › Car brand or the setup's car step.
 *
 * Only [BYD] changes what the engines do: it implies [VehicleProfile.BYD_DILINK]. Every other make
 * implies [VehicleProfile.GENERIC], because neither engine has integrations for any other make;
 * for them the brand only labels this car inside CarLink. Never add behaviour per brand without an
 * engine integration that needs it.
 *
 * The brand is NOT sent to the phone. The CarPlay accessory name and manufacturer, the Android
 * Auto car name and the Wi-Fi Direct identity stay CarLink's ([com.carlink.core.connection.CarLinkConfig.accessoryName]):
 * a phone that saw a new name or make could treat this screen as a new car and forget its saved
 * pairing or network.
 *
 * Order: the mass-market makes by 2025 Indian passenger-vehicle sales (Autocar India, "Top 10
 * carmakers in India in 2025 ranked by sales", 7 January 2026; Skoda Auto India's own 2025 figure
 * of 72,665 units splits the combined Skoda-Volkswagen entry, leaving Volkswagen about 35,600,
 * just behind Renault's 36,420), then the smaller makes, then the luxury makes, then [OTHER].
 */
enum class CarBrand(val displayName: String, val profile: VehicleProfile = VehicleProfile.GENERIC, val luxury: Boolean = false,
                    val motorcycle: Boolean = false) {
    MARUTI_SUZUKI("Maruti Suzuki"),
    MAHINDRA("Mahindra"),
    TATA("Tata Motors"),
    HYUNDAI("Hyundai"),
    TOYOTA("Toyota"),
    KIA("Kia"),
    SKODA("Skoda"),
    MG("MG"),
    HONDA("Honda"),
    RENAULT("Renault"),
    VOLKSWAGEN("Volkswagen"),
    NISSAN("Nissan"),
    CITROEN("Citroën"),
    JEEP("Jeep"),
    BYD("BYD", VehicleProfile.BYD_DILINK),
    MERCEDES_BENZ("Mercedes-Benz", luxury = true),
    BMW("BMW", luxury = true),
    AUDI("Audi", luxury = true),
    VOLVO("Volvo", luxury = true),
    LEXUS("Lexus", luxury = true),

    // Two-wheelers sold in India (issue #1). Like every make but BYD they only label the vehicle.
    HERO("Hero MotoCorp", motorcycle = true),
    HONDA_MOTORCYCLE("Honda Motorcycle", motorcycle = true),
    TVS("TVS Motor", motorcycle = true),
    BAJAJ("Bajaj", motorcycle = true),
    SUZUKI_MOTORCYCLE("Suzuki Motorcycle", motorcycle = true),
    ROYAL_ENFIELD("Royal Enfield", motorcycle = true),
    YAMAHA("Yamaha", motorcycle = true),
    OLA_ELECTRIC("Ola Electric", motorcycle = true),
    ATHER("Ather", motorcycle = true),
    KTM("KTM", motorcycle = true),

    /** A make that is not listed. Behaves as [VehicleProfile.GENERIC]. */
    OTHER("Other"),
    ;

    companion object {
        /** The brand this screen identifies as when the driver never chose: BYD on a BYD DiLink unit, else none. */
        fun detect(context: Context): CarBrand? = detected(VehicleProfile.detect(context))

        /** [BYD] for [VehicleProfile.BYD_DILINK]; null otherwise (a generic screen says nothing about the car). */
        fun detected(profile: VehicleProfile): CarBrand? = if (profile == VehicleProfile.BYD_DILINK) BYD else null

        /** What a choice stored as a [VehicleProfile] (before brands existed) means now. */
        fun fromProfile(profile: VehicleProfile): CarBrand = if (profile == VehicleProfile.BYD_DILINK) BYD else OTHER
    }
}

/**
 * Where the car brand lives: preferences "carlink_vehicle", key [KEY_BRAND]. A choice stored by an
 * older CarLink under [KEY_LEGACY_PROFILE] (a [VehicleProfile] name) is migrated on first read:
 * `BYD_DILINK` becomes [CarBrand.BYD], `GENERIC` becomes [CarBrand.OTHER]. With nothing stored,
 * [chosen] is null and [profile] is what [detect] finds, as before brands existed.
 */
class CarBrandStore(private val prefs: SharedPreferences, private val detect: () -> VehicleProfile) {

    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE),
        { VehicleProfile.detect(context.applicationContext) },
    )

    /** The driver's choice, or null when they never chose. */
    fun chosen(): CarBrand? {
        prefs.getString(KEY_BRAND, null)?.let { name -> runCatching { CarBrand.valueOf(name) }.getOrNull()?.let { return it } }
        val legacy = prefs.getString(KEY_LEGACY_PROFILE, null)
            ?.let { runCatching { VehicleProfile.valueOf(it) }.getOrNull() } ?: return null
        val brand = CarBrand.fromProfile(legacy)
        prefs.edit().putString(KEY_BRAND, brand.name).remove(KEY_LEGACY_PROFILE).apply()
        return brand
    }

    /** The engines' profile: the chosen brand's, or the detected one when nothing was chosen. */
    fun profile(): VehicleProfile = chosen()?.profile ?: detect()

    /** Stores [brand]; true when the profile the engines see changed (the caller re-installs hooks). */
    fun set(brand: CarBrand): Boolean {
        val previous = profile()
        prefs.edit().putString(KEY_BRAND, brand.name).remove(KEY_LEGACY_PROFILE).apply()
        return brand.profile != previous
    }

    companion object {
        const val PREFS = "carlink_vehicle"
        const val KEY_BRAND = "car_brand"
        /** Before 2026-10 CarLink stored only the [VehicleProfile] name here. */
        const val KEY_LEGACY_PROFILE = "vehicle_profile"
    }
}
