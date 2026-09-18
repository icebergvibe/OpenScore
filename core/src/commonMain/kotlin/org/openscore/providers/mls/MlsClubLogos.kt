package org.openscore.providers.mls

/**
 * Official colour crests published by the MLS club catalog. The stats API identifies clubs
 * consistently but does not include image fields in schedules, matches, or standings.
 */
internal object MlsClubLogos {
    private const val BASE = "https://images.mlssoccer.com/image/upload/t_club_logo_medium/"

    private val byTeamId = mapOf(
        "MLS-CLU-000001" to "v1747755309/assets/logos/mls-clubs/Club_Logo-LAFC_djrhru.png",
        "MLS-CLU-000002" to "v1747769446/assets/logos/mls-clubs/Club_Logo-Philadelphia_im7pqg.png",
        "MLS-CLU-000003" to "v1747499975/assets/logos/mls-clubs/Club_Logo-Austin_pa9xtu.png",
        "MLS-CLU-000004" to "v1747756306/assets/logos/mls-clubs/Club_Logo-New_York_City_xu6vax.png",
        "MLS-CLU-000005" to "v1747500567/assets/logos/mls-clubs/Club_Logo-Dallas_sysmtj.png",
        "MLS-CLU-000006" to "v1747755806/assets/logos/mls-clubs/Club_Logo-Montreal_beeqnh.png",
        "MLS-CLU-000007" to "v1747500249/assets/logos/mls-clubs/Club_Logo-Cincinnati_jwgkps.png",
        "MLS-CLU-000008" to "v1747755405/assets/logos/mls-clubs/Club_Logo-Miami_tyqe64.png",
        "MLS-CLU-000009" to "v1747755999/assets/logos/mls-clubs/Club_Logo-Nashville_rb9vwu.png",
        "MLS-CLU-00000A" to "v1775849066/assets/mnp/Club_Logo-Atlanta_ugeyc3_hw47tg.png",
        "MLS-CLU-00000B" to "v1779382319/assets/logos/mls-clubs/RBNY_Logo_v7jpkq.png",
        "MLS-CLU-00000C" to "v1748265547/assets/logos/mls-clubs/Club_Logo-Vancouver_ao9phl.png",
        "MLS-CLU-00000D" to "v1747500504/assets/logos/mls-clubs/Club_Logo-D.C_t03ekm.png",
        "MLS-CLU-00000E" to "v1747500414/assets/logos/mls-clubs/Club_Logo-Columbus_light_z3eq8l.png",
        "MLS-CLU-00000F" to "v1747500178/assets/logos/mls-clubs/Club_Logo-Chicago_jm2yev.png",
        "MLS-CLU-00000G" to "v1747755165/assets/logos/mls-clubs/Club_Logo-LA_Galaxy_fg0wjp.png",
        "MLS-CLU-00000H" to "v1747500939/assets/logos/mls-clubs/Club_Logo-Houston_oifm77.png",
        "MLS-CLU-00000I" to "v1747500045/assets/logos/mls-clubs/Club_Logo-Charlotte_p7sznf.png",
        "MLS-CLU-00000J" to "v1747500322/assets/logos/mls-clubs/Club_Logo-Colorado_n5kpss.png",
        "MLS-CLU-00000K" to "v1747754918/assets/logos/mls-clubs/Club_Logo-Kansas_City_cnhd75.png",
        "MLS-CLU-00000L" to "v1747754826/assets/logos/mls-clubs/Club_Logo-Minnesota_ftweor.png",
        "MLS-CLU-00000M" to "v1748265251/assets/logos/mls-clubs/Club_Logo-Toronto_vz6hao.png",
        "MLS-CLU-00000N" to "v1766020750/assets/NE_Logo_PRI_FC_RGB_480x480_fdx2us.png",
        "MLS-CLU-00000O" to "v1747769281/assets/logos/mls-clubs/Club_Logo-Orlando_ryyn7a.png",
        "MLS-CLU-00000P" to "v1747769540/assets/logos/mls-clubs/Club_Logo-Portland_qihpaz.png",
        "MLS-CLU-00000Q" to "v1748262048/assets/logos/mls-clubs/Club_Logo-San_Jose_opzlmo.png",
        "MLS-CLU-00000R" to "v1747776403/assets/logos/mls-clubs/Club_Logo-Salt_Lake_City_hpvde5.png",
        "MLS-CLU-00000S" to "v1748265125/assets/logos/mls-clubs/Club_Logo-Seattle_e6jk2x.png",
        "MLS-CLU-00001L" to "v1747771858/assets/logos/mls-clubs/Club_Logo-Saint_Louis_guz12c.png",
        "MLS-CLU-000065" to "v1748261519/assets/logos/mls-clubs/Club_Logo-San_Diego_nwpyul.png",
    )

    fun url(teamId: String?): String? = byTeamId[teamId]?.let { BASE + it }
}
