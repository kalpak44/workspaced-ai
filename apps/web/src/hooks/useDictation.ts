import { useEffect, useRef, useState } from "react";

interface Recognition {
  lang: string;
  continuous: boolean;
  interimResults: boolean;
  onresult: ((event: { resultIndex: number; results: ArrayLike<{ isFinal: boolean; [index: number]: { transcript: string } }> }) => void) | null;
  onerror: ((event: { error: string }) => void) | null;
  onend: (() => void) | null;
  start(): void;
  stop(): void;
  abort(): void;
}

type RecognitionConstructor = new () => Recognition;

/** Browser dictation produces ordinary text; no audio is sent to the chat model. */
export function useDictation(onText: (text: string) => void, disabled: boolean) {
  const browser = globalThis as typeof globalThis & {
    SpeechRecognition?: RecognitionConstructor;
    webkitSpeechRecognition?: RecognitionConstructor;
  };
  const Constructor = browser.SpeechRecognition ?? browser.webkitSpeechRecognition;
  const [listening, setListening] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const recognition = useRef<Recognition | null>(null);
  const receive = useRef(onText);
  receive.current = onText;

  const stop = () => recognition.current?.stop();

  useEffect(() => {
    if (disabled) recognition.current?.abort();
  }, [disabled]);

  useEffect(() => () => {
    const current = recognition.current;
    if (current) {
      current.onresult = null;
      current.onerror = null;
      current.onend = null;
      current.abort();
    }
  }, []);

  const toggle = () => {
    if (recognition.current) {
      stop();
      return;
    }
    if (!Constructor || disabled) return;
    const current = new Constructor();
    recognition.current = current;
    current.lang = navigator.language;
    current.continuous = true;
    current.interimResults = false;
    current.onresult = (event) => {
      for (let i = event.resultIndex; i < event.results.length; i++) {
        if (event.results[i].isFinal) receive.current(event.results[i][0].transcript);
      }
    };
    current.onerror = (event) => {
      if (event.error !== "aborted") {
        setError(event.error === "not-allowed" || event.error === "service-not-allowed"
          ? "Microphone access was denied. Allow it in your browser to dictate."
          : "Voice recognition is unavailable. You can still type your message.");
      }
      current.abort();
    };
    current.onend = () => {
      if (recognition.current === current) {
        recognition.current = null;
        setListening(false);
      }
    };
    setError(null);
    try {
      current.start();
      setListening(true);
    } catch {
      recognition.current = null;
      setListening(false);
      setError("Could not start voice recognition. You can still type your message.");
    }
  };

  return { supported: !!Constructor, listening, error, toggle, stop };
}
