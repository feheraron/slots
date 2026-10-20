-- Introducing GiST exclusion constraint
create extension if not exists btree_gist;

-- A slot is a period of a user's calendar
-- A filled slot (title, description and participants via booking) is a meeting
create table slot (
    id           bigint generated always as identity primary key,
    user_id      bigint        not null,
    begin_at     timestamptz   not null,
    end_at       timestamptz   not null,
    status       varchar(4)    not null,
    title        varchar(255),
    description  varchar(1000),
    participants bigint[]      not null default '{}',
    version      integer       not null default 0,
    constraint slot_user_id_check check (user_id > 0),
    constraint slot_end_after_begin check (end_at > begin_at),
    constraint slot_status_check check (status in ('FREE', 'BUSY')),
    constraint slot_meeting_busy check (title is null or status = 'BUSY'),
    constraint slot_no_meeting_details check (title is not null or (description is null and participants = '{}')),

    -- The constraint's index also serves lookups by user and time window (half open)
    constraint slot_no_overlap exclude using gist (user_id with =, tstzrange(begin_at, end_at, '[)') with &&)
);
