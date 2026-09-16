# Wedding Planner — план прототипа

Принцип: одна небольшая функция за раз. Сначала сценарий и критерий готовности, затем реализация,
проверка в браузере и короткая демонстрация результата. Интерфейс остаётся простым; Inbox открывается
сразу, без бокового подраздела. Живые интеграции нельзя выдавать за симуляцию и наоборот.

## Порядок работы

1. Каналы и аккаунты.
   - 1.1 Фильтры Channel и Account, название аккаунта в строке и существующий выбор отправителя.
   - 1.2 Зависимый список аккаунтов выбранного канала; все каналы и достоверные статусы подключения
     видны сразу. Два тестовых почтовых аккаунта, проверка переключения без смешивания адресатов.
2. Контакт и папки.
   - Клиенты, Подрядчики, Партнёры, Площадки, Конкуренты, Команда. Рабочий сценарий — Клиенты.
   - Явное объединение каналов в контакт с подтверждаемым предпросмотром; сохранение адресата каждого
     канала, исходной атрибуции и ссылок на лиды. Карточка → чат → карточка, выбор канала отправки.
3. Ручные коммуникации и звонки.
   - Заметка о телефонном звонке; дата, контакт, автор.
   - Ссылка на Zoom и саммари note taker, без собственной записи/транскрибации звонка.
4. AI и панель лида.
   - Дата свадьбы, пожелания, бюджет.
   - Саммари отдельного чата и всех каналов: стадия, ситуация, следующий шаг.
   - Разделить факты и выводы, показывать актуальность; не менять подтверждённые поля незаметно.
5. Начало воронки.
   - Лид/сделка, горячий/холодный: уточнить точные значения respond.io перед реализацией.
   - Zoom/Calendly из чата с отправкой подтверждения в выбранный исходный чат.
   - Смета → предпроект/проект → Свадьба как демонстрационные переходы; финансовый учёт вне объёма.
6. Живая демонстрация каналов.
   - Тестовые Instagram, WhatsApp и почта; поступление сообщения в реальном времени.
   - Три чата объединяются в контакт, общее AI-саммари обновляется.
   - Проверить поддержку WhatsApp-групп конкретным провайдером до обещания живого сценария.
7. Аналитика и репетиция.
   - Источник, канал, статус, владелец, поток, квалифицированные лиды, мини-графики.
   - Продажа меняет стадию и аналитику без дублирования лидов при объединении контактов.
   - Репетиция согласованного понедельничного демо; точную дату из заметок не предполагаем.

## Основа связи с аналитикой

Contact — человек/пара. Conversation — переписка с определённого Inbox (аккаунта).
LeadInquiry — отдельный интерес к свадьбе с исходным source/channel/campaign и contact.
Несколько чатов и несколько обращений могут принадлежать одному контакту. Объединение контактов
не должно стирать исходный маркетинговый источник и не должно превращать число чатов в число лидов.
Правило атрибуции (первое касание, последнее, другое) необходимо согласовать до шага 7.

## Только обозначаем, не реализуем

- Смета как учётный документ, клиентский бюджет и расходы/финрезультат; документооборот, визы,
  комиссии и прибыль по проекту.
- Разные финансовые права владельцев и организаторов; текущую защиту данных сохраняем.
- Проджект-менеджмент, таск-трекер, анализ договоров.

## Вопросы к соответствующим шагам

- Порядок продажи: бюджет пары → стоимость услуг → договор → смета?
- Конкретные стадии и критерии горячего/холодного лида в respond.io?
- Zoom или Calendly, какой note taker, какой провайдер WhatsApp и его поддержка групп?
- Тестовые аккаунты, разрешения провайдеров, доступ к AI — перед живыми подключениями.
- Что является продажей и как считать маркетинговую атрибуцию?

## Шаг 1.1 — план и критерии

Использовать стандартные фильтры onno и существующие CRM Inbox/Conversation без второй модели каналов.
Channel фильтрует стабильный ключ; Account — UUID аккаунта, название показывает человеку аккаунт.
Фильтры пересекаются. Список аккаунтов на этом шаге общий; зависимый список относится к 1.2.
Опции аккаунтов читаются из активных записей при запросе, не фиксируются при запуске.
В таблице и в чате видны канал и аккаунт. Существующие архивы остаются явно архивами, доставка выключена.
Проверка: все шесть диалогов; один канал; один аккаунт; пересечение; сброс; прежние права доступа.

### Результат 1.1

Реализовано: Channel / Account, динамические опции активных аккаунтов, канал и аккаунт в таблице.
Сборка прошла. Runtime: все 6; Email 1; выбранный аккаунт Email 1; несовместимый канал/аккаунт 0;
сброс 6. В браузере проверены выбор Email и сброс. Следующий шаг — 1.2.

## Уточнение архитектуры

Используем штатный UI и сервисы onno-crm-starter. Отдельный Wedding Planner-инбокс остановлен.
Эксперимент сохранён вне исходников приложения в ../tmp/inbox-experiment для последующего разбора.
Перед продолжением решаем, какие общие возможности расширяют CRM-модуль и какие бизнес-правила
остаются в Wedding Planner. Работающий вариант: CRM 3.0.0, прямой вход в Inbox, фильтры Channel/Account.


## CRM module implementation — 9 September 2026

The native `crmInboxWorkspaces` widget remains the only inbox UI. Shared changes are developed in
`../onno-crm-inbox` (branch `codex/crm-inbox-capabilities`, rebased onto the `v3.1.3` release),
consumed locally as `3.1.3-planner-SNAPSHOT`; no published release is overwritten.

First shared slice: native channel/account controls with server filtering, per-message account
context, manual phone/meeting summaries with recording links, explicitly empty folders and contact
card → inbox navigation. Wedding Planner owns the six folder categories on its existing Contact catalog.
Existing lead sources, campaigns and analytics remain on LeadInquiry.

Next slices: host-owned explicit contact merge preserving inquiry attribution; first-contact phone
creation; connector-backed WhatsApp group intake and real accounts; note-taker ingestion. Account
filter choices currently cover accounts with visible conversations. Manual recording summaries do
not imply an automatic Zoom/note-taker integration. No separate Wedding Planner inbox widget is used.


### Contact merge and navigation implemented

Contact detail now offers **Merge another contact into this one** with the native searchable contact
picker. The current card is retained. The command checks write access, locks CRM consolidation, moves
CRM conversation/identity links and inquiry contact references transactionally, fills only missing
primary email/phone, and soft-deletes the source while retaining its original attributes. A history
event records who merged it and the original contact/email/phone. Repeating the same merge is safe.
Lead source, campaign and original channel, and conversation inbox/routing remain unchanged.
The chat header offers **Open contact** and the contact card offers **Open conversation**.
