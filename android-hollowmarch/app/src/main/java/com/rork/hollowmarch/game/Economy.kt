package com.rork.hollowmarch.game

import com.rork.hollowmarch.game.Textures.valueNoise
import com.rork.hollowmarch.world.Site
import com.rork.hollowmarch.world.World
import com.rork.hollowmarch.world.isSettlement
import kotlin.math.abs
import kotlin.random.Random

/**
 * The economy layer beneath the deep history: what the land could yield, what
 * each settlement sows and reaps, what it eats, which materials it can put its
 * hands on and from where, and who trades with whom. Aggregate throughout —
 * no individual farms, pits, or wagons — and the seed's own: same seed, same
 * years, same ledger, same harvests forever.
 *
 * Terrain reads ([Ecology]) are pure and derived; the mutable residue —
 * timber stocks, crop adoption, deposits, food reserves, trade roads, the
 * last economic identity — rides the history save's fourth section.
 */

// ------------------------------------------------------------------ terrain

/** One settlement's stretch of land, read off the province's noise fields. */
data class LandProfile(
    val elevation: Float,
    val roughness: Float,
    val moisture: Float,
    val forest: Float,
    val water: Float,
    val soil: Float,
    val warmth: Float,
    val saltBand: Float
)

/** What the land around a place is like, in the words the chronicle uses. */
enum class LandCharacter(val label: String) {
    VALLEY("river valley"),
    PLAIN("open plain"),
    FOREST("deep forest"),
    WETLAND("wetland"),
    HILLS("hill country"),
    MOUNTAIN("high mountains")
}

/** The broad resources the land may offer, and whether they come back. */
enum class ResourceKind(val label: String, val renewable: Boolean) {
    GRAIN("grain land", true),
    GRAZING("pasture", true),
    GAME("game", true),
    FISH("fisheries", true),
    TIMBER("timber", true),
    CLAY("clay beds", true),
    STONE("quarry stone", false),
    SALT("salt", false),
    GATHERED("wild gleanings", true),
    ORE("ore", false)
}

/** A 0..1 answer per resource, straight from the land — never a lookup table. */
data class ResourcePotential(
    val grain: Float,
    val grazing: Float,
    val game: Float,
    val fish: Float,
    val timber: Float,
    val clay: Float,
    val stone: Float,
    val salt: Float,
    val gathered: Float
) {
    operator fun get(kind: ResourceKind): Float = when (kind) {
        ResourceKind.GRAIN -> grain
        ResourceKind.GRAZING -> grazing
        ResourceKind.GAME -> game
        ResourceKind.FISH -> fish
        ResourceKind.TIMBER -> timber
        ResourceKind.CLAY -> clay
        ResourceKind.STONE -> stone
        ResourceKind.SALT -> salt
        ResourceKind.GATHERED -> gathered
        ResourceKind.ORE -> 0f // ore is a matter of deposits, not of open land
    }
}

/**
 * The province's ecology: every settlement's land, read from the same noise
 * fields the world itself was drawn with. Pure — the same world always reads
 * the same land.
 */
object Ecology {
    private const val SCALE = 240f

    /** The land one settlement stands on. */
    fun profileOf(world: World, site: Site): LandProfile {
        val x = site.x * SCALE
        val y = site.y * SCALE
        val s = world.seed.toInt()
        val elevation = 0.55f * valueNoise(x * 0.020f, y * 0.020f, s) +
            0.30f * valueNoise(x * 0.055f, y * 0.055f, s + 101) +
            0.15f * valueNoise(x * 0.130f, y * 0.130f, s + 202)
        val roughness = (0.6f * valueNoise(x * 0.075f, y * 0.075f, s + 303) +
            0.4f * valueNoise(x * 0.180f, y * 0.180f, s + 404))
        val moisture = (0.65f * valueNoise(x * 0.045f, y * 0.045f, s + 505) +
            0.35f * valueNoise(x * 0.110f, y * 0.110f, s + 606))
        val forest = (0.55f * moisture + 0.65f * valueNoise(x * 0.060f, y * 0.060f, s + 707) - 0.10f)
            .coerceIn(0f, 1f)
        val riverField = valueNoise(x * 0.030f, y * 0.030f, s + 808)
        val river = (1f - abs(riverField - 0.52f) / 0.06f).coerceIn(0f, 1f)
        val lake = valueNoise(x * 0.090f, y * 0.090f, s + 909)
        val wet = ((0.42f - elevation) / 0.42f).coerceIn(0f, 1f) * moisture
        val water = maxOf(river, lake * 0.7f, wet * 0.9f).coerceIn(0f, 1f)
        val soil = ((0.95f - elevation * 0.75f) * (0.45f + moisture * 0.55f) * (0.75f + river * 0.45f))
            .coerceIn(0f, 1f)
        val warmth = (0.22f + 0.55f * site.y + 0.10f * (1f - elevation)).coerceIn(0f, 1f)
        val saltBand = (1f - abs(valueNoise(x * 0.050f, y * 0.050f, s + 1010) - 0.50f) / 0.035f)
            .coerceIn(0f, 1f)
        return LandProfile(elevation, roughness, moisture, forest, water, soil, warmth, saltBand)
    }

    /** What the land is like, in plain words. */
    fun characterOf(p: LandProfile): LandCharacter = when {
        p.elevation > 0.68f && p.roughness > 0.55f -> LandCharacter.MOUNTAIN
        p.elevation > 0.55f || p.roughness > 0.45f -> LandCharacter.HILLS
        p.water > 0.55f && p.elevation < 0.40f -> LandCharacter.WETLAND
        p.forest > 0.62f -> LandCharacter.FOREST
        p.water > 0.30f && p.soil > 0.50f -> LandCharacter.VALLEY
        else -> LandCharacter.PLAIN
    }

    /** What the land could yield, if hands worked it. */
    fun potentialOf(p: LandProfile): ResourcePotential {
        val grain = (p.soil * (1f - p.roughness * 0.55f) * band(p.warmth, 0.30f, 0.85f))
            .coerceIn(0f, 1f)
        val grazing = ((1f - p.forest * 0.8f) * (1f - p.roughness * 0.7f) *
            band(p.warmth, 0.20f, 0.95f) * (0.4f + p.moisture * 0.6f)).coerceIn(0f, 1f)
        val game = (p.forest * 0.75f + p.roughness * 0.25f + p.water * 0.1f).coerceIn(0f, 1f)
        val fish = p.water
        val timber = p.forest
        val clay = ((p.water * 0.6f + p.soil * 0.4f) * (1f - p.roughness * 0.5f)).coerceIn(0f, 1f)
        val stone = (p.elevation * 0.65f + p.roughness * 0.55f).coerceIn(0f, 1f)
        val salt = p.saltBand
        val gathered = (p.forest * 0.5f + p.water * 0.25f + p.soil * 0.25f).coerceIn(0f, 1f)
        return ResourcePotential(grain, grazing, game, fish, timber, clay, stone, salt, gathered)
    }

    /** 1 inside a band, falling away softly outside it. */
    private fun band(v: Float, lo: Float, hi: Float): Float = when {
        v in lo..hi -> 1f
        v < lo -> (1f - (lo - v) / 0.15f).coerceIn(0f, 1f)
        else -> (1f - (v - hi) / 0.15f).coerceIn(0f, 1f)
    }
}

// -------------------------------------------------------------------- crops

/**
 * The five crops the province knows, and what each asks of the land. A crop
 * sown on land that cannot feed it never takes root.
 */
enum class CropKind(
    val label: String,
    val minWarmth: Float,
    val maxWarmth: Float,
    val minMoisture: Float,
    val maxMoisture: Float,
    val minSoil: Float
) {
    GRAIN("grain", 0.30f, 0.80f, 0.25f, 0.75f, 0.35f),
    BARLEY("hardy barley", 0.15f, 0.70f, 0.15f, 0.65f, 0.22f),
    ROOTS("roots", 0.10f, 0.75f, 0.20f, 0.80f, 0.20f),
    FRUIT("orchard fruit", 0.40f, 0.95f, 0.30f, 0.70f, 0.40f),
    GREENS("greens and fiber", 0.30f, 0.85f, 0.35f, 0.85f, 0.30f);

    /** How well this crop would do on [p], 0..1 — pure from the land. */
    fun suitability(p: LandProfile): Float {
        val warm = softBand(p.warmth, minWarmth, maxWarmth)
        val wet = softBand(p.moisture, minMoisture, maxMoisture)
        val dirt = if (p.soil >= minSoil) {
            0.6f + 0.4f * ((p.soil - minSoil) / (1f - minSoil).coerceAtLeast(0.01f))
        } else {
            p.soil / minSoil.coerceAtLeast(0.01f) * 0.5f
        }
        return (warm * wet * dirt).coerceIn(0f, 1f)
    }

    private fun softBand(v: Float, lo: Float, hi: Float): Float = when {
        v in lo..hi -> 1f
        v < lo -> (1f - (lo - v) / 0.15f).coerceIn(0f, 1f)
        else -> (1f - (v - hi) / 0.15f).coerceIn(0f, 1f)
    }
}

/** Where a crop stands in a place's fields. */
enum class CropStage { UNKNOWN, CULTIVATED, ESTABLISHED }

// ------------------------------------------------------------------ deposits

/** A pocket of metal in the ground: how much it held, and how much is left. */
data class DepositState(
    val material: Material,
    val richness: Int,
    val remaining: Int,
    val discovered: Boolean
)

/** A trade road between two places, carrying one resource. */
data class TradeRoute(val fromId: Int, val toId: Int, val resource: ResourceKind) {
    val key: String get() = "${minOf(fromId, toId)}>${maxOf(fromId, toId)}:${resource.name}"
}

/** Where a settlement's material comes from, in one word. */
enum class MaterialSource { LOCAL, IMPORTED, SCARCE }

/** The answer to "can this place obtain this material, and from where?". */
data class MaterialProvenance(
    val material: Material,
    val source: MaterialSource,
    val fromSiteId: Int = -1,
    val fromSiteName: String = ""
)

// ------------------------------------------------------------- the simulation

/**
 * The living economy of every settlement, turned year by year beneath the
 * folk ledger. Pure in its rolls: every chance is drawn from the seed, the
 * place, and the year — never from luck.
 */
class EconomySimulation private constructor(private val world: World) {

    private val geography = MaterialGeography(world)

    // derived terrain, never saved
    private val profiles = mutableMapOf<Int, LandProfile>()
    private val potentials = mutableMapOf<Int, ResourcePotential>()

    // mutable residue, saved
    private val states = mutableMapOf<Int, EcoState>()

    /** One place's economic residue: fields, woods, diggings, granary, roads. */
    private class EcoState(val siteId: Int) {
        var timber: Int = 0
        var timberInit: Int = 0
        var timberGone: Boolean = false
        var fishPressure: Int = 0 // thousandths
        var gamePressure: Int = 0 // thousandths
        var reserve: Int = 0
        var richYear: Boolean = false
        var famineStreak: Int = 0
        val crops = LinkedHashMap<CropKind, Int>() // crop -> years cultivated
        val deposits = mutableListOf<MutableDeposit>()
        val routes = LinkedHashSet<String>()
        var labels: List<String>? = null
    }

    /** The working form of a deposit while the economy runs. */
    private class MutableDeposit(
        val material: Material,
        val richness: Int,
        var remaining: Int,
        var discovered: Boolean
    )

    // ------------------------------------------------------------- land reads

    fun profileOf(site: Site): LandProfile =
        profiles.getOrPut(site.id) { Ecology.profileOf(world, site) }

    fun potentialOf(site: Site): ResourcePotential =
        potentials.getOrPut(site.id) { Ecology.potentialOf(profileOf(site)) }

    fun characterOf(site: Site): LandCharacter = Ecology.characterOf(profileOf(site))

    /** The culture whose region law governs a place's ground. */
    internal fun cultureOf(site: Site): Int =
        site.holderPowerId?.let { holder -> world.powers.firstOrNull { it.id == holder }?.cultureId } ?: -1

    // ------------------------------------------------------------ state reads

    private fun stateOf(site: Site): EcoState = states.getOrPut(site.id) { initState(site) }

    private fun initState(site: Site): EcoState {
        val st = EcoState(site.id)
        val pot = potentialOf(site)
        st.timber = (pot.timber * 1200f).toInt().coerceAtLeast(0)
        st.timberInit = st.timber
        val rng = Random(world.seed * 7919L + site.id * 104729L + 17L)
        val available = geography.available(cultureOf(site)).toSet()
        val common = listOf(
            Material.BOG_IRON, Material.COPPER, Material.IRON,
            Material.LEAD, Material.SILVER, Material.GOLD
        ).filter { it in available }
        val rare = listOf(Material.STAR_IRON, Material.SPIRIT_SILVER, Material.EARTH_BONE)
            .filter { it in available }
        val count = when (characterOf(site)) {
            LandCharacter.MOUNTAIN -> 1 + rng.nextInt(2)
            LandCharacter.HILLS -> rng.nextInt(2)
            else -> if (rng.nextInt(100) < 35) 1 else 0
        }
        if (common.isNotEmpty()) {
            repeat(count) {
                val material = if (rare.isNotEmpty() && rng.nextInt(100) < 22) {
                    rare[rng.nextInt(rare.size)]
                } else {
                    common[rng.nextInt(common.size)]
                }
                val richness = 400 + rng.nextInt(1400)
                st.deposits += MutableDeposit(material, richness, richness, discovered = false)
            }
        }
        return st
    }

    /** The standing wood about a place, in logs. */
    fun timberOf(site: Site): Int = stateOf(site).timber

    /** What stands in a place's granary. */
    fun reserveOf(site: Site): Int = stateOf(site).reserve

    /** How a crop stands in a place's fields. */
    fun cropStageOf(site: Site, crop: CropKind): CropStage {
        val years = stateOf(site).crops[crop] ?: return CropStage.UNKNOWN
        return if (years >= ESTABLISH_YEARS) CropStage.ESTABLISHED else CropStage.CULTIVATED
    }

    /** Every crop's stage at a place. */
    fun cropsOf(site: Site): Map<CropKind, CropStage> =
        CropKind.entries.associateWith { cropStageOf(site, it) }
            .filterValues { it != CropStage.UNKNOWN }

    /** A place's diggings, frozen for reading. */
    fun depositsOf(site: Site): List<DepositState> =
        stateOf(site).deposits.map { DepositState(it.material, it.richness, it.remaining, it.discovered) }

    /** The roads a place keeps, parsed for reading. */
    fun routesOf(site: Site): List<TradeRoute> =
        stateOf(site).routes.mapNotNull { key -> parseRoute(key) }

    /** A place's economic identity, derived from what it actually produces. */
    fun specializationOf(site: Site, folk: Int): List<String> {
        val st = stateOf(site)
        return st.labels ?: labelSet(site, folk, st)
    }

    // ------------------------------------------------------------- the years

    /**
     * Turn the economy forward to [year] for every living settlement. Writes
     * its events into [into] and returns the places whose shortfall sends
     * hungry walkers onto the roads — the count that walks, keyed by site.
     * [folkOverride] lets a caller stand folk where the ledger has none yet;
     * the engine never uses it.
     */
    fun stepYear(
        year: Int,
        sites: List<Site>,
        ledger: SettlementLedger,
        into: MutableList<AgeEvent>,
        folkOverride: Map<Int, Int> = emptyMap()
    ): Map<Int, Int> {
        val hunger = mutableMapOf<Int, Int>()
        val living = sites.filter { it.isSettlement && !it.ruined }.sortedBy { it.id }
        for (site in living) {
            val st = stateOf(site)
            val folk = (folkOverride[site.id] ?: ledger.folkOf(site)).coerceAtLeast(0)
            val pot = potentialOf(site)
            stepCrops(year, site, st)
            val yields = yieldsOf(site, folk, st, pot)
            stepPressure(folk, st, pot)
            stepTimber(year, site, folk, st, pot, into)
            stepDeposits(year, site, folk, st, into)
            stepFood(year, site, folk, st, pot, yields, living, into, hunger)
            stepTrade(year, site, st, living, ledger, into)
            stepSpecialization(year, site, folk, st, into)
        }
        return hunger
    }

    /** Crops arrive by seed and trade, and yearly the fields grow surer. */
    private fun stepCrops(year: Int, site: Site, st: EcoState) {
        val prof = profileOf(site)
        for (crop in CropKind.entries) {
            val suit = crop.suitability(prof)
            val years = st.crops[crop]
            if (years == null) {
                if (suit < ADOPT_FLOOR) continue
                val chance = suit * 0.28f + if (st.routes.isNotEmpty()) 0.05f else 0f
                if (rng(site.id, year, 31 + crop.ordinal).nextFloat() < chance) {
                    st.crops[crop] = 0
                }
            } else {
                st.crops[crop] = years + 1
            }
        }
    }

    /** What a place produces in a year, in food, from its fields and waters.
     * The land has only so many hands' worth of ground: past its breadth the
     * yield per soul thins, and the place must trade or walk. */
    private fun yieldsOf(site: Site, folk: Int, st: EcoState, pot: ResourcePotential): FloatArray {
        val hands = folk.coerceAtMost(LAND_FOLK)
        val cropFood = st.crops.entries.sumOf { (crop, years) ->
            val stage = if (years >= ESTABLISH_YEARS) 1f else 0.55f
            (pot.grain * crop.suitability(profileOf(site)) * stage * hands * CROP_RATE).toDouble()
        }.toFloat()
        val meat = pot.grazing * hands * MEAT_RATE
        val game = pot.game * (1f - st.gamePressure / 1000f) * hands * GAME_RATE
        val fish = pot.fish * (1f - st.fishPressure / 1000f) * hands * FISH_RATE
        val gathered = pot.gathered * hands * GATHER_RATE
        return floatArrayOf(cropFood, meat, game, fish, gathered)
    }

    /** The waters and woods feel the hand that works them, and heal when left. */
    private fun stepPressure(folk: Int, st: EcoState, pot: ResourcePotential) {
        val fishStrain = (folk * 1000f / (pot.fish * 20000f + 500f)).toInt()
        st.fishPressure = (st.fishPressure + fishStrain - FISH_REGEN).coerceIn(0, PRESSURE_CAP)
        val gameStrain = (folk * 1000f / (pot.game * 40000f + 1000f)).toInt()
        st.gamePressure = (st.gamePressure + gameStrain - GAME_REGEN).coerceIn(0, PRESSURE_CAP)
    }

    /** Wood is cut, the forest grows back; cut harder than it grows and it goes. */
    private fun stepTimber(
        year: Int,
        site: Site,
        folk: Int,
        st: EcoState,
        pot: ResourcePotential,
        into: MutableList<AgeEvent>
    ) {
        if (st.timberInit <= 0) return
        val cut = minOf(st.timber, folk / TIMBER_FOLK_PER_LOG)
        st.timber -= cut
        val regrowth = (pot.timber * 60f).toInt().coerceAtLeast(2)
        st.timber = minOf(st.timberInit, st.timber + regrowth)
        if (!st.timberGone && st.timberInit >= 300 && st.timber < st.timberInit / 10) {
            st.timberGone = true
            into += AgeEvent(
                year, AgeEventKind.RESOURCE_DEPLETED, site.id,
                text = "The woods about ${site.name} are cut out; its folk must look abroad for timber."
            )
        }
    }

    /** Diggings are found by growing folk, worked, and in time worked out. */
    private fun stepDeposits(year: Int, site: Site, folk: Int, st: EcoState, into: MutableList<AgeEvent>) {
        val character = characterOf(site)
        for (dep in st.deposits) {
            if (!dep.discovered) {
                val chance = 0.02f + folk / 4000f +
                    if (character == LandCharacter.MOUNTAIN || character == LandCharacter.HILLS) 0.08f else 0.02f
                if (rng(site.id, year, 61 + dep.material.ordinal).nextFloat() < chance) {
                    dep.discovered = true
                    into += AgeEvent(
                        year, AgeEventKind.RESOURCE_DISCOVERED, site.id,
                        text = "Veins of ${dep.material.label} were found in the ground about ${site.name}."
                    )
                }
            } else if (dep.remaining > 0) {
                val take = (folk / ORE_FOLK_PER_LOAD).coerceAtMost(ORE_MAX_LOAD).coerceAtLeast(if (folk > 0) 2 else 0)
                dep.remaining = (dep.remaining - take).coerceAtLeast(0)
                if (dep.remaining == 0) {
                    into += AgeEvent(
                        year, AgeEventKind.RESOURCE_DEPLETED, site.id,
                        text = "The ${dep.material.label} diggings at ${site.name} are worked out; the pits stand empty."
                    )
                }
            }
        }
    }

    /** The granary: what the year grew, what the roads brought, what was eaten. */
    private fun stepFood(
        year: Int,
        site: Site,
        folk: Int,
        st: EcoState,
        pot: ResourcePotential,
        yields: FloatArray,
        living: List<Site>,
        into: MutableList<AgeEvent>,
        hunger: MutableMap<Int, Int>
    ) {
        val production = yields[0] + yields[1] + yields[2] + yields[3] + yields[4]
        var imported = 0f
        for (key in st.routes) {
            val route = parseRoute(key) ?: continue
            val otherId = if (route.fromId == site.id) route.toId else route.fromId
            val partner = living.firstOrNull { it.id == otherId } ?: continue
            val ppot = potentialOf(partner)
            if (route.resource in FOOD_RESOURCES && ppot[route.resource] > 0.45f) {
                imported += (ppot[route.resource] * folk * IMPORT_RATE)
                    .coerceAtMost(folk * FOOD_NEED * 0.25f)
            }
        }
        val need = folk * FOOD_NEED
        val intake = production + imported
        val spoil = st.reserve / 25
        st.reserve = (st.reserve + (intake - need).toInt() - spoil).coerceAtLeast(0)
        val deficit = (need - intake).toInt()
        // Granaries buffer the first lean years; only want that outlasts them
        // puts hungry souls onto the roads.
        if (deficit > 0 && production < need * 0.75f) {
            st.famineStreak++
            if (st.famineStreak >= FAMINE_STREAK_YEARS) {
                val hungry = (deficit / FOOD_NEED).coerceAtMost(folk / 3)
                if (hungry > 0) {
                    hunger[site.id] = hungry
                    if (hungry >= folk / 5) {
                        into += AgeEvent(
                            year, AgeEventKind.HARVEST_FAILURE, site.id, count = hungry,
                            text = "The harvest failed at ${site.name}; the granary stands empty and ${hungry} souls went hungry."
                        )
                    }
                }
                st.famineStreak = 0
            }
        } else {
            st.famineStreak = 0
        }
        if (!st.richYear && production > need * SURPLUS_GAIN && st.reserve > folk * RESERVE_RICH) {
            st.richYear = true
            into += AgeEvent(
                year, AgeEventKind.HARVEST_SURPLUS, site.id,
                text = "Granaries at ${site.name} stand full — a fat year the folk will remember."
            )
        }
        if (st.richYear && st.reserve <= folk * RESERVE_RICH / 3) st.richYear = false
    }

    /** Roads are beaten between what one place has and another lacks. */
    private fun stepTrade(
        year: Int,
        site: Site,
        st: EcoState,
        living: List<Site>,
        ledger: SettlementLedger,
        into: MutableList<AgeEvent>
    ) {
        val partners = living.filter { it.id != site.id }
            .map { it to distSq(site, it) }
            .filter { it.second <= TRADE_MAX_DIST_SQ }
            .sortedWith(compareBy({ it.second }, { it.first.id }))
            .take(TRADE_PARTNERS)
            .map { it.first }
            .filter { ledger.folkOf(it) > ROUTE_MIN_FOLK }

        for (partner in partners) {
            if (site.id > partner.id) continue // each pair once, alphabet of ids
            val resource = complementaryResource(site, partner) ?: continue
            val key = TradeRoute(site.id, partner.id, resource).key
            if (st.routes.add(key)) {
                stateOf(partner).routes.add(key)
                into += AgeEvent(
                    year, AgeEventKind.TRADE_ROUTE_OPENED, site.id, partner.id,
                    text = "A trade road was opened between ${site.name} and ${partner.name} — " +
                        "${partner.name} lacks ${resource.label} and ${site.name} has it to spare."
                )
            }
        }

        // A road is kept while its other end stands within reach and the
        // complement still holds — whichever place first beat the road open.
        val dead = st.routes.filter { key ->
            val route = parseRoute(key) ?: return@filter false
            val otherId = if (route.fromId == site.id) route.toId else route.fromId
            val other = living.firstOrNull { it.id == otherId }
            val withinReach = other != null && distSq(site, other) <= TRADE_MAX_DIST_SQ &&
                ledger.folkOf(other) > ROUTE_MIN_FOLK
            !(withinReach && routeStillComplements(route))
        }
        for (key in dead) {
            val route = parseRoute(key) ?: continue
            val otherId = if (route.fromId == site.id) route.toId else route.fromId
            st.routes.remove(key)
            states[otherId]?.routes?.remove(key)
            val other = living.firstOrNull { it.id == otherId }
            into += AgeEvent(
                year, AgeEventKind.TRADE_ROUTE_CLOSED, site.id, otherId,
                text = "The trade road between ${site.name} and ${other?.name ?: "a dead place"} fell out of use."
            )
        }
    }

    /** What a place is known for, and the year it became known for it. */
    private fun stepSpecialization(
        year: Int,
        site: Site,
        folk: Int,
        st: EcoState,
        into: MutableList<AgeEvent>
    ) {
        val newLabels = labelSet(site, folk, st)
        val prev = st.labels
        if (prev != null && newLabels != prev && folk >= SPECIALIZE_MIN_FOLK) {
            into += AgeEvent(
                year, AgeEventKind.SPECIALIZED, site.id,
                text = "The folk of ${site.name} are now known for " +
                    newLabels.joinToString(" and ") + "; the old days of " +
                    prev.joinToString(" and ") + " are past."
            )
        }
        st.labels = newLabels
    }

    // ---------------------------------------------------------- the trade law

    /** The one resource whose complement would bind two places together. */
    private fun complementaryResource(a: Site, b: Site): ResourceKind? {
        val apot = potentialOf(a)
        val bpot = potentialOf(b)
        for (kind in ResourceKind.entries) {
            if (kind == ResourceKind.ORE) continue
            if (apot[kind] >= TRADE_RICH && bpot[kind] <= TRADE_POOR) return kind
        }
        // ore rides the roads only when one place has a working digging and the other none
        val aOre = stateOf(a).deposits.any { it.discovered && it.remaining > 0 }
        val bOre = stateOf(b).deposits.any { it.discovered && it.remaining > 0 }
        if (aOre && !bOre && a.id < b.id) return ResourceKind.ORE
        return null
    }

    private fun routeStillComplements(route: TradeRoute): Boolean {
        if (route.resource == ResourceKind.ORE) {
            val a = world.sites.firstOrNull { it.id == route.fromId } ?: return false
            return stateOf(a).deposits.any { it.discovered && it.remaining > 0 }
        }
        val a = world.sites.firstOrNull { it.id == route.fromId } ?: return false
        val b = world.sites.firstOrNull { it.id == route.toId } ?: return false
        val apot = potentialOf(a)
        val bpot = potentialOf(b)
        return apot[route.resource] >= TRADE_RICH && bpot[route.resource] <= TRADE_POOR
    }

    private fun parseRoute(key: String): TradeRoute? {
        val halves = key.split(">")
        if (halves.size != 2) return null
        val rest = halves[1].split(":")
        if (rest.size != 2) return null
        val fromId = halves[0].toIntOrNull() ?: return null
        val toId = rest[0].toIntOrNull() ?: return null
        val resource = ResourceKind.entries.firstOrNull { it.name == rest[1] } ?: return null
        return TradeRoute(fromId, toId, resource)
    }

    // -------------------------------------------------------- the material law

    /** What the ground and woods of a place yield with its own hands. */
    private fun localMaterials(site: Site, folk: Int, st: EcoState): Set<Material> {
        val pot = potentialOf(site)
        val local = mutableSetOf<Material>()
        for (dep in st.deposits) {
            if (dep.discovered && dep.remaining > 0) local += dep.material
        }
        if (st.timber > 0 && pot.timber > 0.25f) local += Material.ASHWOOD
        if (pot.grazing > 0.30f || pot.game > 0.30f) local += Material.BONE
        if (Material.COPPER in local) local += Material.VERDIGRIS
        val forgeTown = folk >= TOWN_FOLK
        if (forgeTown && (Material.COPPER in local || Material.LEAD in local)) local += Material.BRASS
        if (forgeTown && (Material.IRON in local || Material.BOG_IRON in local)) local += Material.STEEL
        if (pot.salt > 0.5f) local += Material.SALT_GLASS
        if (pot.stone > 0.55f) local += Material.GRAVE_SLATE
        return local
    }

    /**
     * The full material report for a place: every material the world knows,
     * marked LOCAL, IMPORTED (naming the road's other end), or SCARCE.
     */
    fun materialReport(site: Site, ledger: SettlementLedger, sites: List<Site>): List<MaterialProvenance> =
        Material.entries.map { provenanceOf(site, ledger, sites, it) }

    /** One material's answer: can this place obtain it, and from where? */
    fun provenanceOf(site: Site, ledger: SettlementLedger, sites: List<Site>, material: Material): MaterialProvenance {
        val st = stateOf(site)
        val regionAvailable = material in geography.available(cultureOf(site))
        if (regionAvailable && material in localMaterials(site, ledger.folkOf(site).coerceAtLeast(0), st)) {
            return MaterialProvenance(material, MaterialSource.LOCAL)
        }
        if (!regionAvailable) return MaterialProvenance(material, MaterialSource.SCARCE)
        // one road's reach: a partner's own hands, not a partner's imports
        for (key in st.routes) {
            val route = parseRoute(key) ?: continue
            val otherId = if (route.fromId == site.id) route.toId else route.fromId
            val partner = sites.firstOrNull { it.id == otherId } ?: continue
            if (material in localMaterials(partner, ledger.folkOf(partner).coerceAtLeast(0), stateOf(partner))) {
                return MaterialProvenance(material, MaterialSource.IMPORTED, partner.id, partner.name)
            }
        }
        return MaterialProvenance(material, MaterialSource.SCARCE)
    }

    // ------------------------------------------------------------ the identity

    /** The work a place is known by, from what it actually produces. */
    private fun labelSet(site: Site, folk: Int, st: EcoState): List<String> {
        if (folk <= 0) return emptyList()
        val pot = potentialOf(site)
        val labels = mutableListOf<String>()
        val yields = yieldsOf(site, folk, st, pot)
        val need = folk * FOOD_NEED
        if (yields[0] > need * 0.7f) labels += "farming"
        if (yields[3] > need * 0.35f && pot.fish > 0.35f) labels += "fishing"
        if (pot.grazing * MEAT_RATE > FOOD_NEED * 0.35f) labels += "pastoral"
        if (st.timber > 100 && pot.timber > 0.45f) labels += "logging"
        if (st.deposits.any { it.discovered && it.remaining > 0 }) labels += "mining"
        if (pot.clay > 0.5f && folk >= 100) labels += "brickworking"
        if (pot.stone > 0.55f && folk >= 100) labels += "quarrying"
        if (pot.salt > 0.5f) labels += "salting"
        if (st.routes.size >= 2) labels += "trade hub"
        if (labels.isEmpty()) labels += "gleaning"
        return labels
    }

    // ------------------------------------------------------------------- save

    /** The economy's residue, packed: one entry per touched place. */
    fun encode(): String = states.entries.sortedBy { it.key }.joinToString(ENTRY) { (id, st) ->
        "S$id" +
            "=${st.timber}" +
            "=${if (st.timberGone) 1 else 0}" +
            "=${st.fishPressure}" +
            "=${st.gamePressure}" +
            "=${st.reserve}" +
            "=${if (st.richYear) 1 else 0}" +
            "=${st.crops.entries.joinToString(",") { "${it.key.name}:${it.value}" }}" +
            "=${st.deposits.joinToString(",") { "${it.material.name}:${it.remaining}:${if (it.discovered) 1 else 0}" }}" +
            "=${st.routes.sorted().joinToString(",")}" +
            "=${(st.labels ?: emptyList()).joinToString(",")}" +
            "=${st.famineStreak}"
    }

    /** Wake from the save; a blank string wakes clean, as old saves always have. */
    fun applyEncoded(raw: String) {
        if (raw.isBlank()) return
        raw.split(ENTRY).forEach { piece ->
            val parts = piece.split("=")
            if (parts.size < 10 || !parts[0].startsWith("S")) return@forEach
            val id = parts[0].removePrefix("S").toIntOrNull() ?: return@forEach
            val site = world.sites.firstOrNull { it.id == id } ?: return@forEach
            val st = stateOf(site)
            st.timber = parts[1].toIntOrNull() ?: st.timber
            st.timberGone = parts[2] == "1"
            st.fishPressure = (parts[3].toIntOrNull() ?: 0).coerceIn(0, PRESSURE_CAP)
            st.gamePressure = (parts[4].toIntOrNull() ?: 0).coerceIn(0, PRESSURE_CAP)
            st.reserve = parts[5].toIntOrNull() ?: 0
            st.richYear = parts[6] == "1"
            parts[7].takeIf { it.isNotBlank() }?.split(",")?.forEach { entry ->
                val kv = entry.split(":")
                val crop = CropKind.entries.firstOrNull { it.name == kv[0] } ?: return@forEach
                st.crops[crop] = kv.getOrNull(1)?.toIntOrNull() ?: 0
            }
            parts[8].takeIf { it.isNotBlank() }?.split(",")?.forEach { entry ->
                val kv = entry.split(":")
                val material = Material.entries.firstOrNull { it.name == kv[0] } ?: return@forEach
                val dep = st.deposits.firstOrNull { it.material == material } ?: return@forEach
                dep.remaining = kv.getOrNull(1)?.toIntOrNull() ?: dep.remaining
                dep.discovered = kv.getOrNull(2) == "1"
            }
            parts[9].takeIf { it.isNotBlank() }?.split(",")?.filter { it.isNotBlank() }?.let {
                st.routes.addAll(it)
            }
            parts.getOrNull(10)?.takeIf { it.isNotBlank() }?.split(",")?.let { st.labels = it }
            st.famineStreak = parts.getOrNull(11)?.toIntOrNull() ?: 0
        }
    }

    // ----------------------------------------------------------------- engine

    private fun rng(siteId: Int, year: Int, salt: Int): Random =
        Random(world.seed * 1103515245L + siteId * 100003L + year * 7919L + salt)

    private fun distSq(a: Site, b: Site): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return dx * dx + dy * dy
    }

    companion object {
        /** The economy's own arithmetic, tuned once, in one place. */
        const val ESTABLISH_YEARS = 4
        const val FOOD_NEED = 10
        /** The hands' worth of ground one settlement's land can work at full yield. */
        const val LAND_FOLK = 10000
        /** Lean years a granary can absorb before the folk start walking. */
        const val FAMINE_STREAK_YEARS = 3
        const val CROP_RATE = 14f
        const val MEAT_RATE = 10f
        const val GAME_RATE = 4f
        const val FISH_RATE = 12f
        const val GATHER_RATE = 5f
        const val IMPORT_RATE = 6f
        const val TIMBER_FOLK_PER_LOG = 8
        const val ORE_FOLK_PER_LOAD = 12
        const val ORE_MAX_LOAD = 80
        const val FISH_REGEN = 40
        const val GAME_REGEN = 30
        const val PRESSURE_CAP = 850
        const val TRADE_PARTNERS = 2
        const val TRADE_MAX_DIST_SQ = 0.09f
        const val TRADE_RICH = 0.6f
        const val TRADE_POOR = 0.25f
        const val ROUTE_MIN_FOLK = 5
        const val ADOPT_FLOOR = 0.10f
        const val SURPLUS_GAIN = 1.45f
        const val RESERVE_RICH = 8
        const val TOWN_FOLK = 320
        const val SPECIALIZE_MIN_FOLK = 40

        private const val ENTRY = "\u001E"

        private val FOOD_RESOURCES = setOf(
            ResourceKind.GRAIN, ResourceKind.GRAZING, ResourceKind.GAME,
            ResourceKind.FISH, ResourceKind.GATHERED
        )

        /** A fresh economy for a fresh world: untouched ground everywhere. */
        fun fresh(world: World): EconomySimulation = EconomySimulation(world)
    }
}
