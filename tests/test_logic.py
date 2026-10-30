"""Koltuk planı, harita ayrıştırma ve bildirim kararı."""
import datetime as dt
import json
import os
import unittest
from pathlib import Path

import seats
import tcdd_alarm
from seats import Kind, Prefs, Seat, make_plan, parse_seatmap, tr_norm


def seat(no, row, pos, free=True, gender=None, wagon="1", kind=Kind.ECONOMY, rot=None, group=None, direction=None, blk=None, restrict=None):
    return Seat(wagon, str(no), row, pos, free, gender, direction, group, kind, rot, blk, restrict)


def prefs(*g, kinds=None, side=False, facing=False, direction="A", strict=False):
    if kinds is None:
        kinds = {Kind.ECONOMY, Kind.BUSINESS}
    return Prefs(list(g), side, facing, direction, strict, kinds)


def wagon_json(train_car_on_sale=True, restrict_first=None):
    seats_json = []
    for r in range(6):
        for i, y in enumerate((0, 2, 6, 8)):
            no = r * 4 + i + 1
            seats_json.append(
                {
                    "seatNumber": str(no),
                    "positionX": r * 2,
                    "positionY": y,
                    "rotation": 0 if r % 2 == 0 else 180,
                    "isAllocated": False,
                    "genderRestriction": restrict_first if no == 1 else None,
                    "item": {"saleable": True, "itemType": {"name": "SEAT"}},
                }
            )
    return {
        "data": {
            "seatMaps": [
                {
                    "trainCarId": 7,
                    "trainCarIndex": 3,
                    "trainCarOnSale": train_car_on_sale,
                    "seatMapTemplate": {"car": {"name": "EKONOMİ"}, "seatMaps": seats_json},
                    "allocationSeats": [{"seatNumber": "2", "genders": ["MALE"]}],
                }
            ]
        }
    }


TRAIN_JSON = {
    "trainLegs": [
        {
            "trainAvailabilities": [
                {
                    "trains": [
                        {
                            "id": 172246,
                            "name": "81001",
                            "commercialName": "YHT ANKARA - İSTANBUL",
                            "departureStationId": 98,
                            "arrivalStationId": 48,
                            "trainSegments": [
                                {"departureTime": 1793300000000, "segment": {"departureStation": {"id": 77}}},
                                {"departureTime": 1793350800000, "segment": {"departureStation": {"id": 98}}},
                            ],
                            "cabinClassAvailabilities": [
                                {"cabinClass": {"name": "EKONOMİ"}, "availabilityCount": 7},
                                {"cabinClass": {"name": "BUSİNESS"}, "availabilityCount": 3},
                                {"cabinClass": {"name": "TEKERLEKLİ SANDALYE"}, "availabilityCount": 2},
                            ],
                        }
                    ]
                }
            ]
        }
    ]
}


class PlannerTests(unittest.TestCase):
    def test_gender_rule(self):
        rows = [seat(1, 0, 0), seat(2, 0, 1, free=False, gender="E"), seat(3, 0, 2), seat(4, 0, 3)]
        self.assertEqual(make_plan(rows, prefs("K")).ids, ["1-3"])
        self.assertEqual(make_plan(rows, prefs("E")).ids, ["1-1"])

    def test_unknown_neighbor_gender_does_not_block(self):
        rows = [seat(1, 0, 0), seat(2, 0, 1, free=False, gender=None), seat(3, 0, 2, free=False, gender="E")]
        self.assertEqual(make_plan(rows, prefs("K")).ids, ["1-1"])

    def test_side_by_side(self):
        rows = [
            seat(1, 0, 0), seat(2, 0, 1, free=False, gender="K"), seat(3, 0, 2), seat(4, 0, 3),
            seat(5, 1, 0), seat(6, 1, 1),
        ]
        plan = make_plan(rows, prefs("K", "E", side=True))
        self.assertEqual(plan.ids, ["1-3", "1-4"])
        self.assertEqual(plan.unmet, [])

    def test_kind_filter(self):
        rows = [seat(1, 0, 0, kind=Kind.BUSINESS), seat(2, 0, 1, kind=Kind.WHEELCHAIR), seat(3, 0, 2)]
        self.assertEqual(make_plan(rows, prefs("K", kinds={Kind.ECONOMY})).ids, ["1-3"])
        self.assertEqual(make_plan(rows, prefs("K", kinds={Kind.BUSINESS})).ids, ["1-1"])
        self.assertEqual(make_plan(rows, prefs("K", kinds={Kind.WHEELCHAIR})).ids, ["1-2"])
        self.assertIsNone(make_plan(rows, prefs("K", kinds=set())))

    def test_strict_direction(self):
        rows = [seat(1, 0, 0, rot=180, direction="B"), seat(2, 0, 1, rot=180, direction="B")]
        self.assertIsNone(make_plan(rows, prefs("K", kinds={Kind.ECONOMY}, direction="F", strict=True)))
        soft = make_plan(rows, prefs("K", kinds={Kind.ECONOMY}, direction="F", strict=False))
        self.assertEqual(soft.unmet, ["koltuk yönü"])

    def test_facing(self):
        rows = [
            seat(1, 0, 0, rot=0, group="g1"), seat(5, 1, 0, rot=180, group="g1"),
            seat(2, 0, 1, rot=0, group="g1"), seat(6, 1, 1, rot=180, group="g1"),
        ]
        plan = make_plan(rows, prefs("E", "E", facing=True))
        self.assertEqual(plan.unmet, [])
        self.assertEqual(len({s.rot for s in plan.seats}), 2)

    def test_missing_facing_is_reported(self):
        plan = make_plan([seat(1, 0, 0), seat(2, 0, 1)], prefs("K", "E", facing=True))
        self.assertTrue(any("Karşılıklı" in n for n in plan.notes))

    def test_restriction(self):
        rows = [seat(1, 0, 0, restrict="K"), seat(2, 0, 2)]
        self.assertEqual(make_plan(rows, prefs("E")).ids, ["1-2"])
        self.assertEqual(make_plan(rows, prefs("K")).ids, ["1-1"])

    def test_three_same_row(self):
        rows = [seat(i + 1, 0, i) for i in range(4)] + [seat(i + 5, 1, i) for i in range(4)]
        plan = make_plan(rows, prefs("K", "K", "E", side=True))
        self.assertEqual(len({s.row for s in plan.seats}), 1)
        self.assertEqual(plan.unmet, [])

    def test_five_passengers_same_row(self):
        rows = [seat(i + 1, 0, i) for i in range(6)]
        plan = make_plan(rows, prefs("K", "K", "E", "E", "K", side=True))
        self.assertEqual(len(plan.seats), 5)
        self.assertEqual(len({s.row for s in plan.seats}), 1)
        self.assertEqual(plan.unmet, [])

    def test_tr_norm(self):
        self.assertEqual(tr_norm("İSTANBUL(PENDİK)"), "istanbul(pendik)")
        self.assertEqual(tr_norm("Istanbul"), "istanbul")
        self.assertEqual(tr_norm("ESKİŞEHİR"), "eskisehir")
        self.assertEqual(tr_norm("BUSİNESS"), "business")
        self.assertEqual(tr_norm("BUSINESS"), "business")


class ParseTests(unittest.TestCase):
    def test_real_schema(self):
        sm = parse_seatmap(wagon_json())
        by = {s.no: s for s in sm}
        self.assertEqual(len(sm), 24)
        self.assertEqual(by["1"].wagon, "3")
        self.assertTrue(by["1"].free)
        self.assertFalse(by["2"].free)
        self.assertEqual(by["2"].gender, "E")
        self.assertEqual(by["1"].blk, by["2"].blk)
        self.assertNotEqual(by["1"].blk, by["3"].blk)
        self.assertIsNotNone(by["1"].group)
        self.assertEqual(by["1"].group, by["5"].group)
        self.assertEqual(by["3"].group, by["7"].group)
        self.assertNotEqual(by["1"].group, by["3"].group)
        self.assertEqual(by["1"].kind, Kind.ECONOMY)

    def test_gender_rule_on_parsed_map(self):
        sm = parse_seatmap(wagon_json())
        plan = make_plan(sm, Prefs(["K"], kinds={Kind.ECONOMY}))
        self.assertNotIn("3-1", plan.ids)

    def test_facing_from_schema(self):
        sm = parse_seatmap(wagon_json())
        plan = make_plan(sm, Prefs(["E", "E"], facing=True, kinds={Kind.ECONOMY}))
        self.assertEqual(plan.unmet, [])
        self.assertEqual(len({s.group for s in plan.seats}), 1)
        self.assertEqual(len({s.rot for s in plan.seats}), 2)

    def test_direction_needs_calibration(self):
        none = parse_seatmap(wagon_json(), None)
        plan = make_plan(none, Prefs(["E"], direction="F", kinds={Kind.ECONOMY}))
        self.assertTrue(any("yön" in n for n in plan.notes))
        cal = {s.no: s for s in parse_seatmap(wagon_json(), 180)}
        self.assertEqual(cal["5"].direction, "F")
        self.assertEqual(cal["1"].direction, "B")

    def test_restriction_and_off_sale(self):
        restricted = {s.no: s for s in parse_seatmap(wagon_json(restrict_first="FEMALE"))}
        self.assertEqual(restricted["1"].restrict, "K")
        self.assertTrue(all(not s.free for s in parse_seatmap(wagon_json(train_car_on_sale=False))))

    def test_unknown_schema(self):
        self.assertEqual(parse_seatmap({"foo": [1, 2, 3]}), [])

    def test_stations(self):
        raw = json.loads(
            '[{"id":98,"name":"ANKARA GAR","ticketSaleActive":true,"pairs":[48,{"id":60}]},'
            '{"id":5,"name":"KAPALI","ticketSaleActive":false},'
            '{"id":48,"name":"İSTANBUL(PENDİK)","pairs":["98"]}]'
        )
        st = tcdd_alarm.parse_stations(raw)
        self.assertEqual(len(st), 2)
        self.assertEqual(st[0]["name"], "ANKARA GAR")
        self.assertEqual(st[0]["pairs"], [48, 60])
        self.assertEqual(st[1]["pairs"], [98])

    def test_trains_and_boarding_segment(self):
        eco = tcdd_alarm.extract_trains(TRAIN_JSON, {Kind.ECONOMY})
        self.assertEqual(len(eco), 1)
        self.assertEqual(eco[0]["name"], "YHT ANKARA - İSTANBUL")
        self.assertEqual(eco[0]["seats"], 7)
        self.assertEqual(eco[0]["id"], 172246)
        self.assertEqual(int(eco[0]["departure"].timestamp() * 1000), 1793350800000)
        all_kinds = tcdd_alarm.extract_trains(TRAIN_JSON, {Kind.ECONOMY, Kind.BUSINESS, Kind.WHEELCHAIR})
        self.assertEqual(all_kinds[0]["seats"], 12)
        wheel = tcdd_alarm.extract_trains(TRAIN_JSON, {Kind.WHEELCHAIR})
        self.assertEqual(wheel[0]["seats"], 2)
        self.assertEqual(tcdd_alarm.extract_trains({"x": 1}, {Kind.ECONOMY}), [])

    def test_date_param(self):
        self.assertEqual(tcdd_alarm.date_param("2026-11-15"), "14-11-2026 21:00:00")
        self.assertEqual(tcdd_alarm.date_param("2027-01-01"), "31-12-2026 21:00:00")

    def test_in_range_uses_istanbul(self):
        utc = dt.datetime(2026, 11, 15, 6, 0, tzinfo=dt.timezone.utc)
        self.assertTrue(tcdd_alarm.in_range(utc, "08:00", "10:00"))
        self.assertFalse(tcdd_alarm.in_range(utc, "05:00", "06:30"))


class AlertTests(unittest.TestCase):
    def setUp(self):
        self.sent = []
        self.prefs = prefs("K", "E", side=True, kinds={Kind.ECONOMY})
        self.key = "2026-11-15|YHT|09:30"
        self.route = "A → B"

    def send(self, subject, body):
        self.sent.append((subject, body))

    def train(self, n):
        return {"seats": n, "cabins": {"EKONOMİ": n}, "name": "YHT"}

    def handle(self, state, train, rows):
        tcdd_alarm.handle_train(state, self.key, train, rows, self.prefs, self.send, self.route)

    def test_notifies_once(self):
        state = {"active": {}, "count_notified": set()}
        rows = [seat(1, 0, 0), seat(2, 0, 1), seat(3, 0, 2)]
        for _ in range(3):
            self.handle(state, self.train(3), rows)
        self.assertEqual(len(self.sent), 1)
        self.assertIn("Boş yer var", self.sent[0][0])

    def test_new_suggestion_when_sold(self):
        state = {"active": {}, "count_notified": set()}
        rows = [seat(1, 0, 0), seat(2, 0, 1), seat(3, 0, 2)]
        self.handle(state, self.train(3), rows)
        first = list(state["active"][self.key])
        sold = []
        for s in rows:
            if s.sid == first[0]:
                sold.append(Seat(s.wagon, s.no, s.row, s.pos, False, "K", s.direction, s.group, s.kind))
            else:
                sold.append(s)
        self.handle(state, self.train(2), sold)
        self.assertEqual(len(self.sent), 2)
        self.assertIn("satıldı", self.sent[1][0])
        self.assertNotEqual(state["active"][self.key], first)
        self.handle(state, self.train(2), sold)
        self.assertEqual(len(self.sent), 2)

    def test_reopens_after_full(self):
        state = {"active": {}, "count_notified": set()}
        rows = [seat(1, 0, 0), seat(2, 0, 1)]
        self.handle(state, self.train(2), rows)
        full = [Seat(s.wagon, s.no, s.row, s.pos, False, s.gender, s.direction, s.group, s.kind) for s in rows]
        self.handle(state, self.train(0), full)
        self.assertEqual(state["active"], {})
        self.handle(state, self.train(2), rows)
        self.assertEqual(len(self.sent), 2)

    def test_count_only_once_and_keeps_active_plan(self):
        state = {"active": {}, "count_notified": set()}
        for _ in range(3):
            self.handle(state, self.train(4), [])
        self.assertEqual(len(self.sent), 1)
        self.assertIn("okunamadı", self.sent[0][1])
        rows = [seat(1, 0, 0), seat(2, 0, 1)]
        self.handle(state, self.train(2), rows)
        self.handle(state, self.train(2), [])
        self.assertEqual(len(self.sent), 2)
        self.assertIn(self.key, state["active"])

    def test_not_enough_seats(self):
        self.handle({"active": {}, "count_notified": set()}, self.train(1), [seat(1, 0, 0)])
        self.assertEqual(self.sent, [])

    def test_strict_stays_silent(self):
        self.prefs = prefs("K", "E", side=True, strict=True, kinds={Kind.ECONOMY})
        rows = [seat(1, 0, 0), seat(2, 0, 1, free=False), seat(3, 0, 2), seat(4, 0, 3, free=False)]
        self.handle({"active": {}, "count_notified": set()}, self.train(2), rows)
        self.assertEqual(self.sent, [])

    def test_state_roundtrip(self):
        original = tcdd_alarm.STATE_FILE
        try:
            tcdd_alarm.STATE_FILE = Path(self.id().replace(".", "_") + "-state.json")
            state = {"active": {self.key: ["1-1", "1-2"]}, "count_notified": {"x|y|z"}}
            tcdd_alarm.save_state(state)
            back = tcdd_alarm.load_state()
            self.assertEqual(back["active"], state["active"])
            self.assertEqual(back["count_notified"], state["count_notified"])
        finally:
            tcdd_alarm.STATE_FILE = original
            path = Path(self.id().replace(".", "_") + "-state.json")
            if path.exists():
                path.unlink()


class NotifyTests(unittest.TestCase):
    def test_missing_env(self):
        saved = {k: os.environ.pop(k, None) for k in ("TELEGRAM_BOT_TOKEN", "TELEGRAM_CHAT_ID", "SMTP_HOST", "SMTP_USER", "SMTP_PASSWORD")}
        try:
            self.assertIn("TELEGRAM_BOT_TOKEN", tcdd_alarm.notify.missing_env("telegram"))
            self.assertIn("SMTP_HOST", tcdd_alarm.notify.missing_env("both"))
            self.assertEqual(tcdd_alarm.notify.missing_env("console"), [])
        finally:
            for k, v in saved.items():
                if v is not None:
                    os.environ[k] = v


if __name__ == "__main__":
    unittest.main()
