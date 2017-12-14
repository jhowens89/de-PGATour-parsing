import com.beust.klaxon.*
import java.io.BufferedInputStream
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.ceil

class WgcParser {

    /*
     * Leaderboard
     */

    data class Round(val roundNumber: String,
                     val matches: List<Match>)

    data class Match(val match: String,
                     val poolNumber: String,
                     val groupId: String,
                     val scoreStatus: String,
                     val matchStatus: String,
                     val players: List<Player>,
                     var holes: List<Hole> = emptyList())

    data class Player(val pid: String,
                      val isMatchWinner: Boolean,
                      val isLeading: Boolean,
                      val playerBio: PlayerBio)

    data class PlayerBio(val firstName: String,
                         val lastName: String,
                         val country: String,
                         val seed: String,
                         val wins: String,
                         val losses: String,
                         val halves: String)

    data class Hole(val hole: String,
                    val winner: String,
                    val scoreStatus: String)

    /*
     * Scorecard json containers
     */
    data class MatchDetails(val leader: String,
                            val winner: String,
                            val matchStatus: String,
                            val scoreStatus: String,
                            val playerScorecards: List<PlayerScorecard>,
                            val lastUpdated: String = "2017-10-01T19:35-04:00")

    data class PlayerScorecard(val pid: String,
                               val holes: List<Hole>,
                               val scorecard: Scorecard)

    data class Scorecard(val courseName: String,
                         val thru: String,
                         val scoringType: String,
                         val hostCourse: Boolean,
                         val roundScorecard: RoundScorecard)

    data class RoundScorecard(val currentRound: Boolean,
                              val courseId: String,
                              val round: String,
                              val currentHole: String,
                              val groupId: String,
                              val holes: List<HoleDetails>)

    data class HoleDetails(val gir: Boolean,
                           val roundToPar: String,
                           val holeStatus: String,
                           val putts: String,
                           val strokes: String,
                           val yards: String,
                           val hole: String,
                           val fir: Boolean,
                           val par: String,
                           val toPar: String,
                           val status: String,
                           val playByPlay: PlayByPlay)

    data class PlayByPlay(val shots: List<Shot>)

    data class Shot(val stroke: String,
                    val from: Coordinate,
                    val point: Coordinate,
                    val distance: String,
                    val cup: Boolean,
                    val positionDescription: String,
                    val distToPin: String,
                    val description: String,
                    val timestamp: String,
                    val type: String)


    data class Coordinate(val x: String, val y: String, val z: String)


    /*
     * Intermediate container
     */
    data class StaticHoleSetupInfo(val holeCamera: Coordinate,
                                   val holeTarget: Coordinate,
                                   val greenCamera: Coordinate,
                                   val greenTarget: Coordinate)

    data class AllHoleSetupInfo(val staticInfo: StaticHoleSetupInfo,
                                val tee: Coordinate,
                                val pin: Coordinate)


    companion object {


        @JvmStatic
        fun main(args: Array<String>) {


            val parser = Parser()

            val file = File("round-play.json")
            file.mkdirs()
            file.createNewFile()


            val writer = BufferedWriter(FileWriter(file))


            val roundPlayerToGroupIdMap = mutableMapOf<String, String>()
            val teeTimesUrl = URL("https://s3.amazonaws.com/de-pgat/DellMatchPlay/teetimes.json")

            try {
                val urlConnection = teeTimesUrl.openConnection() as HttpURLConnection
                val teeTimesIn = BufferedInputStream(urlConnection.inputStream)
                val completeJsonObject = parser.parse(teeTimesIn) as JsonObject

                completeJsonObject.array<JsonObject>("rounds")?.forEach {
                    val roundNumber = it.string("round")!!
                    it.array<JsonObject>("groups")?.forEach { group ->
                        val groupId = group.string("group_id")!!

                        group.array<JsonObject>("players")?.forEach { player ->
                            roundPlayerToGroupIdMap.put(createRoundPlayerKey(roundNumber, player.string("pid")!!), groupId)
                        }
                    }
                }

            } finally {

            }


            var rounds = emptyList<Round>()
            val leaderboardUrl = URL("https://statdata.pgatour.com/r/470/2017/leaderboard_mp.json")
            val urlConnection = leaderboardUrl.openConnection() as HttpURLConnection

            try {


                val leaderboardIn = BufferedInputStream(urlConnection.inputStream)
                val completeJsonObject = parser.parse(leaderboardIn) as JsonObject


                val roundArray = completeJsonObject.array<JsonObject>("rounds")


                rounds = roundArray?.map {

                    val roundNumber = it.string("roundNum")!!

                    val nestedMatches = it.array<JsonObject>("brackets")?.map {
                        it.array<JsonObject>("groups")?.map { matchJson ->
                            val players = matchJson.array<JsonObject>("players")?.map { playerJson ->
                                Player(pid = playerJson.string("pid")!!,
                                        isMatchWinner = playerJson.string("matchWinner")!! == "Yes",
                                        isLeading = playerJson.string("matchLeader")!! == "Yes",
                                        playerBio = PlayerBio(
                                                firstName = playerJson.string("fName")!!,
                                                lastName = playerJson.string("lName")!!,
                                                country = playerJson.string("country")!!,
                                                seed = playerJson.string("seed")!!,
                                                wins = playerJson.string("poolWins")!!,
                                                losses = playerJson.string("poolLosses")!!,
                                                halves = playerJson.string("poolHalves")!!))
                            }.orEmpty()


                            val scoreStatus = matchJson.array<JsonObject>("players")?.get(0)?.string("finalMatchScr").orEmpty()
                            val matchStatus = "Complete"



                            Match(match = matchJson.string("matchNum")!!,
                                    poolNumber = matchJson.string("poolNum")!!,
                                    groupId = roundPlayerToGroupIdMap.get("$roundNumber-${players.first().pid}")!!,
                                    scoreStatus = scoreStatus,
                                    matchStatus = matchStatus,
                                    players = players)
                        }.orEmpty()
                    }.orEmpty()

                    val matches = nestedMatches.flatten()

                    Round(roundNumber, matches)
                }.orEmpty()

            } finally {
                urlConnection.disconnect()
            }


            val fullRounds = rounds.subList(0, 3).map { round ->

                val matches = round.matches.map { match ->

                    val matchUrl = "https://statdata.pgatour.com/r/470/mp_matches/r${round.roundNumber}-m${match.match}.json"
                    val urlConnection = URL(matchUrl).openConnection() as HttpURLConnection

                    try {
                        val matchIn = BufferedInputStream(urlConnection.inputStream)
                        val completeJsonObject = parser.parse(matchIn) as JsonObject
                        /*
                         * For leaderboard and hole overview part of scorecard
                         */
                        val holes = completeJsonObject.array<JsonObject>("players")?.get(0)?.array<JsonObject>("holes")?.map { hole ->
                            val holeNumber = hole.string("seqNum")!!
                            val scoreStatus = hole.string("tournStatus")!!
                            val winner = when (hole.string("holeStatus")!!.toIntOrNull()) {
                                1 -> match.players.first().pid
                                -1 -> match.players.last().pid
                                else -> ""
                            }
                            Hole(hole = holeNumber,
                                    scoreStatus = scoreStatus,
                                    winner = winner)
                        }.orEmpty()

                        match.copy(holes = holes)


                        /*
                         * Scorecard parsing and file generation
                         */
                        //Gathering hole specific coordinates/camera info
                        val holeInfoMap = createHoleInfoMap(completeJsonObject)

                        val leader = match.players.find { it.isLeading }?.pid.orEmpty()
                        val winner = match.players.find { it.isMatchWinner }?.pid.orEmpty()

                        val courseName = completeJsonObject.obj("course")?.string("course_name")
                        //val courseCode = completeJsonObject.obj("course")?.string("course_code")
                        val courseId = completeJsonObject.obj("course")?.string("course_id")
                        val playerScorecards = createPlayerScorecards(completeJsonObject, holeInfoMap, round, holes, courseName, courseId, roundPlayerToGroupIdMap)

                        val matchDetails = MatchDetails(leader = leader,
                                winner = winner,
                                matchStatus = match.matchStatus,
                                scoreStatus = match.scoreStatus,
                                playerScorecards = playerScorecards)

                        //TODO: see if file title needs to correspond to group number instead
                        writeScorecardToFile(round.roundNumber, match.match, matchDetails)

                        //Return plain old match for the summary collection that is leaderboard
                        match
                    } finally {
                        urlConnection.disconnect()
                    }
                }
                round.copy(matches = matches)
            }


            /*
             * leaderboard-matchplay (just rounds 1-3)
             */
            val jsonResult = convertDataToLeaderboardJson(fullRounds)

            writer.write(jsonResult.toJsonString(true))
            writer.close()


        }

        private fun convertDataToLeaderboardJson(fullRounds: List<Round>): JsonArray<Any?> {
            return json {

                array(fullRounds.map { round ->
                    obj(
                            "round" to round.roundNumber,
                            "matches" to array(round.matches.map { match ->
                                obj(
                                        "match" to match.match,
                                        "group_id" to match.groupId,
                                        "pool_number" to match.poolNumber,
                                        "score_status" to match.scoreStatus,
                                        "match_status" to match.matchStatus,
                                        "players" to array(match.players.map { player ->
                                            obj(
                                                    "pid" to player.pid,
                                                    "is_match_winner" to player.isMatchWinner,
                                                    "is_leading" to player.isLeading,
                                                    "player_bio" to obj(
                                                            "first_name" to player.playerBio.firstName,
                                                            "last_name" to player.playerBio.lastName,
                                                            "country" to player.playerBio.country,
                                                            "seed" to player.playerBio.seed,
                                                            "wins" to player.playerBio.wins,
                                                            "losses" to player.playerBio.losses,
                                                            "halves" to player.playerBio.halves))
                                        }),
                                        "holes" to createHolesSummaryJsonArray(match.holes))
                            }))
                })

            }
        }

        private fun JSON.createHolesSummaryJsonArray(holes: List<Hole>): JsonArray<Any?> {
            return array(holes.map { hole ->
                obj(
                        "hole" to hole.hole,
                        "winner" to hole.winner,
                        "score_status" to hole.scoreStatus)
            })
        }

        private fun createHoleInfoMap(completeJsonObject: JsonObject): Map<String, AllHoleSetupInfo> {
            return completeJsonObject.obj("course")?.array<JsonObject>("holes")?.flatMap { holeObj ->
                val holeCamera = Coordinate(holeObj.string("hole_camera_x").orEmpty(), holeObj.string("hole_camera_y").orEmpty(), holeObj.string("hole_camera_z").orEmpty())
                val holeTarget = Coordinate(holeObj.string("hole_target_x").orEmpty(), holeObj.string("hole_target_y").orEmpty(), holeObj.string("hole_target_z").orEmpty())
                val greenCamera = Coordinate(holeObj.string("green_camera_x").orEmpty(), holeObj.string("green_camera_y").orEmpty(), holeObj.string("green_camera_z").orEmpty())
                val greenTarget = Coordinate(holeObj.string("green_target_x").orEmpty(), holeObj.string("green_target_y").orEmpty(), holeObj.string("green_target_z").orEmpty())

                val staticInfo = StaticHoleSetupInfo(holeCamera, holeTarget, greenCamera, greenTarget)
                val holeId = holeObj.string("hole_id").orEmpty()


                holeObj.array<JsonObject>("round")?.map { roundHoleObj ->
                    with(roundHoleObj) {
                        val roundNumber = roundHoleObj.string("round_num")!!

                        val allInfo = AllHoleSetupInfo(staticInfo = staticInfo,
                                tee = Coordinate(roundHoleObj.string("tee_x").orEmpty(), roundHoleObj.string("tee_y").orEmpty(), roundHoleObj.string("tee_z").orEmpty()),
                                pin = Coordinate(roundHoleObj.string("pin_x").orEmpty(), roundHoleObj.string("pin_y").orEmpty(), roundHoleObj.string("pin_z").orEmpty()))

                        val key = createHoleRoundKey(holeId, roundNumber)
                        Pair(key, allInfo)
                    }
                }.orEmpty()
            }?.toMap().orEmpty()
        }

        private fun createPlayerScorecards(completeJsonObject: JsonObject, holeInfoMap: Map<String, AllHoleSetupInfo>, round: Round, holes: List<Hole>, courseName: String?, courseId: String?, groupIdMap: MutableMap<String, String>): List<PlayerScorecard> {
            return completeJsonObject.array<JsonObject>("players")?.map { player ->
                val pid = player.string("pid")

                val holeDetailsList = player.array<JsonObject>("holes")?.map { holeDetails ->
                    with(holeDetails) {
                        //TODO: dafuq is gir
                        val gir = false//TODO: dafuq is this
                        val roundToPar = ""//TODO: dafuq is this
                        val holeStatus = string("holeStatus")
                        val putts = "dafuq is this"
                        val strokes = string("strokes")
                        val yards = string("ydsOfficial")//TODO: Or ydsActual?
                        val hole = string("seqNum")
                        val fir = false//TODO: dafuq is this
                        val par = string("par")
                        val toPar = "dafuq is this"

                        var previousShot: Coordinate? = holeInfoMap[createHoleRoundKey(hole!!, round.roundNumber)]?.tee
                        val shots = array<JsonObject>("shots")?.map { shot ->
                            with(shot) {
                                val from = previousShot ?: Coordinate("0", "0", "0")
                                val point = Coordinate(string("x").orEmpty(), string("y").orEmpty(), string("x").orEmpty())
                                previousShot = point

                                val distance = int("distance")
                                val distToPin =  int("left")
                                val stroke = int("shot_id")

                                Shot(stroke = "$stroke",
                                        from = from,
                                        point = point,
                                        distance = distance?.let { "${it / 36}" }.orEmpty(),
                                        cup = boolean("cup")!!,
                                        positionDescription = "dafuq is this",
                                        distToPin = distToPin?.let { convertInchesToStringDistance(it) }.orEmpty(),
                                        description = string("shottext").orEmpty(),
                                        timestamp = "dafuq is this (needs conversion from some kinda int)",
                                        type = "dafuq is this (maybe just '-')")
                            }
                        }.orEmpty()

                        HoleDetails(gir = gir,
                                roundToPar = roundToPar!!,
                                holeStatus = holeStatus!!,
                                putts = putts!!,
                                strokes = strokes!!,
                                yards = yards!!,
                                hole = hole!!,
                                fir = fir!!,
                                par = par!!,
                                toPar = toPar!!,
                                status = holes.find { hole == it.hole }!!.scoreStatus,
                                playByPlay = PlayByPlay(shots = shots))
                    }
                }.orEmpty()

                val groupId = groupIdMap[createRoundPlayerKey(round.roundNumber, pid!!)].orEmpty()
                
                PlayerScorecard(pid = pid!!,
                        holes = holes,
                        scorecard = Scorecard(courseName = courseName!!,
                                thru = "dafuq is this value come from",
                                scoringType = "dafuq is this",
                                hostCourse = true,//dafuq,
                                roundScorecard = RoundScorecard(
                                        currentRound = false,//TODO: needs outside info
                                        courseId = courseId!!,
                                        round = round.roundNumber,
                                        currentHole = "dafuq is this value come from",
                                        groupId = groupId,
                                        holes = holeDetailsList
                                )))

            }.orEmpty()
        }

        private fun writeScorecardToFile(roundNumber: String, fileTitle: String, matchDetails: MatchDetails) {
            val directory = File("470/group_scorecard/$roundNumber")
            directory.mkdirs()
            val groupScorecardFile = File(directory, "$fileTitle.json")
            groupScorecardFile.createNewFile()
            val writer = BufferedWriter(FileWriter(groupScorecardFile))

            val scorecardJson = convertDataToScorecardJson(matchDetails)
            writer.write(scorecardJson.toJsonString(true))
            writer.close()
        }

        private fun convertDataToScorecardJson(matchDetails: MatchDetails): JsonObject {
            return json {
                obj(
                        "leader" to matchDetails.leader,
                        "match_status" to matchDetails.matchStatus,
                        "format" to "FCS",//<--TODO figure out if this is right
                        "scorecards" to array(matchDetails.playerScorecards.map { playerScorecard ->
                            with(playerScorecard.scorecard) {
                                obj(
                                        "holes" to createHolesSummaryJsonArray(playerScorecard.holes),
                                        "pid" to playerScorecard.pid,
                                        "scorecard" to obj(
                                                "round_scorecard" to obj(
                                                        "current_round" to roundScorecard.currentRound,
                                                        "round" to roundScorecard.round,
                                                        "course_id" to roundScorecard.courseId,
                                                        "current_hole" to roundScorecard.currentHole,
                                                        "group_id" to roundScorecard.groupId,
                                                        "holes" to array(roundScorecard.holes.map { hole ->
                                                            obj(
                                                                    "gir" to hole.gir,
                                                                    "round_to_par" to hole.roundToPar,
                                                                    "hole_status" to hole.holeStatus,
                                                                    "putts" to hole.putts,
                                                                    "strokes" to hole.strokes,
                                                                    "pbp" to obj("shots" to array(hole.playByPlay.shots.map { shot ->
                                                                        obj(
                                                                                "stroke" to shot.stroke,
                                                                                "distance" to shot.distance,
                                                                                "from" to shot.from.toJsonObj(),
                                                                                "point" to shot.point.toJsonObj(),
                                                                                "cup" to shot.cup,
                                                                                "position_description" to shot.positionDescription,
                                                                                "dist_to_pin" to shot.distToPin,
                                                                                "description" to shot.description,
                                                                                "timestamp" to shot.timestamp,
                                                                                "type" to shot.type
                                                                        )
                                                                    })),
                                                                    "yards" to hole.yards,
                                                                    "hole" to hole.hole,
                                                                    "fir" to hole.fir,
                                                                    "par" to hole.par,
                                                                    "to_par" to hole.toPar,
                                                                    "status" to hole.status
                                                            )
                                                        })
                                                ),
                                                "course_name" to courseName,
                                                "thru" to thru,
                                                "scoring_type" to scoringType,
                                                "host_course" to hostCourse
                                        ))
                            }
                        }))

            }
        }

        private fun Coordinate.toJsonObj(): JsonObject {
            return with(this) {
                JsonObject(mapOf("x" to x, "y" to y, "z" to z))
            }
        }


        /*
         * Small util functions
         */
        private fun createRoundPlayerKey(roundNumber: String, pid: String) = "$roundNumber-$pid"

        private fun createHoleRoundKey(holeId: String, roundNumber: String) = "$holeId-$roundNumber"

        private fun convertInchesToStringDistance(it: Int): String {
            return if (it / 36 == 0) {
                "${it % 36}''"
            } else if (it / 36 < 10) {
                //guessing
                "${ceil(it / 12.0)}'"
            } else {
                "${it / 36}"
            }
        }
    }


}