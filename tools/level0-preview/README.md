# Level 0 — превью генерации

Интерактивная карта Level 0 сверху с ползунками. Работает локально из файла,
ничего собирать не нужно.

## Открыть

Двойной клик по `index.html` или:

```sh
xdg-open tools/level0-preview/index.html
```

## Что видно на карте

- жёлтое — вырезанное пространство (коридоры, лучи, комнаты);
- тёмное — сплошная масса стен;
- красноватые пиксели — вырезано, но недостижимо из точки входа;
- голубой крестик — точка входа `/backrooms enter`;
- красный крест — запертая комната (её центр недостижим).

Серые чипы под картой: процент вырезанного, достижимость от спавна, число
запертых комнат, число комнат в кадре, диапазон высот пола.

## Как применить настройки

1. Покрути ползунки, посмотри на карту.
2. Нажми **«Скачать config.json»** — файл `level0-config.json` сохранится
   (обычно в `~/Downloads`).
3. Скажи в чате, что конфиг готов.

Значения из JSON переносятся в константы `Level0Plan.java` (ключи совпадают
1:1), затем мод пересобирается. После этого новые значения становятся
дефолтом в игре.

Кнопка **«Скопировать JSON»** кладёт тот же конфиг в буфер обмена, а
**«Показать JSON»** — в текстовое поле.

## Точность модели

`core.js` — порт `Level0Plan.java`: тот же 64-битный хеш, тот же value noise,
те же соли и те же формулы. `DEFAULT_CONFIG` совпадает с константами игры.

Превью показывает план сверху: интерьеры 45 вариантов комнат (колонны, ямы,
мезонины и т.п.) не рисуются, но факт комнаты, её размер, тип, плоский пол и
лучи учитываются.

Проверка связности повторяет правила игры: подъём не больше 0.5 блока за шаг,
спуск — любой. Статистика считается по внутреннему окну с отступом 48 блоков
от края кадра, потому что край кадра обрезает мир и не является стеной.

## Соответствие JSON → Level0Plan.java

| JSON                | Java                     |
|---------------------|--------------------------|
| `seed`              | `maze_seed` в dimension JSON и `new Level0Plan(...)` в командах |
| `web1Spacing`       | `WEB1_SPACING`           |
| `web2Spacing`       | `WEB2_SPACING`           |
| `web3Spacing`       | `WEB3_SPACING`           |
| `web3WidthFactor`   | `WEB3_WIDTH_FACTOR`      |
| `widthSpacing`      | `WIDTH_SPACING`          |
| `widthBase`         | `WIDTH_BASE`             |
| `widthBreathMin`    | `WIDTH_BREATH_MIN`       |
| `widthBreathRange`  | `WIDTH_BREATH_RANGE`     |
| `widthGrowMin`      | `WIDTH_GROW_MIN`         |
| `widthGrowMax`      | `WIDTH_GROW_MAX`         |
| `growDistance`      | `GROW_DISTANCE`          |
| `floorSpacing`      | `FLOOR_SPACING`          |
| `floorLevels`       | `FLOOR_LEVELS`           |
| `roomNearGate`      | `ROOM_NEAR_GATE`         |
| `roomFarGate`       | `ROOM_FAR_GATE`          |
| `roomNearRadius`    | `ROOM_NEAR_RADIUS`       |
| `roomSizeMin`       | `ROOM_SIZE_MIN`          |
| `roomSizeRandom`    | `ROOM_SIZE_RANDOM`       |
| `rayCount`          | `RAY_COUNT`              |
| `rayMinLen`         | `RAY_MIN_LEN`            |
| `rayLenRandom`      | `RAY_LEN_RANDOM`         |
| `rayMaxReach`       | `RAY_MAX_REACH`          |
| `rayBlindOneIn`     | `RAY_BLIND_ONE_IN`       |
| `rayBlindLen`       | `RAY_BLIND_LEN`          |

Служебные ключи `_view`, `_stats`, `_generatedAt` нужны только для удобства и
при внедрении игнорируются.
