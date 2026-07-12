package com.poker.room.application;

import com.poker.engine.cards.Deck;

@FunctionalInterface
public interface DeckFactory { Deck create(); }
