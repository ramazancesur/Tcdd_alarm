"""Ağ olmadan denenebilir 2+2 vagon.

Her tick bir boş koltuğu satılmış sayar.
"""

from __future__ import annotations


class DemoWorld:
    """Sahte koltuk haritaları üretir."""

    def __init__(self) -> None:
        self.sold: dict[tuple, set[str]] = {}

    def seatmap(self, key, tick: bool = True) -> dict:
        sold = self.sold.setdefault(key, {"2"})
        if tick:
            for number in range(1, 25):
                label = str(number)
                if label not in sold:
                    sold.add(label)
                    break
        return _wagon(sold)


def _wagon(sold: set[str]) -> dict:
    seats = []
    for row in range(6):
        for index, cross in enumerate((0, 2, 6, 8)):
            number = str(row * 4 + index + 1)
            seats.append(
                {
                    "seatNumber": number,
                    "positionX": row * 2,
                    "positionY": cross,
                    "rotation": 0 if row % 2 == 0 else 180,
                    "isAllocated": number in sold and number != "2",
                    "genderRestriction": None,
                    "item": {
                        "saleable": True,
                        "itemType": {"name": "SEAT"},
                    },
                }
            )
    allocation = [
        {
            "seatNumber": number,
            "genders": ["MALE"] if number == "2" else [],
        }
        for number in sorted(sold, key=int)
    ]
    return {
        "data": {
            "seatMaps": [
                {
                    "trainCarId": 7,
                    "trainCarIndex": 3,
                    "trainCarOnSale": True,
                    "seatMapTemplate": {
                        "car": {"name": "EKONOMİ"},
                        "seatMaps": seats,
                    },
                    "allocationSeats": allocation,
                }
            ]
        }
    }
