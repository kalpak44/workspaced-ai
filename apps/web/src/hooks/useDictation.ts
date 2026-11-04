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

/**
 * Browser dictation produces ordinary text; no audio is sent to the chat model. What is heard
 * shows as a live transcript while recording and is handed over once, when recording ends.
 * Interim results count too, since some browsers never mark the last phrase final.
 */
export function useDictation(onText: (text: string) => void, disabled: boolean) {
  const browser = globalThis as typeof globalThis & {
    SpeechRecognition?: RecognitionConstructor;
    webkitSpeechRecognition?: RecognitionConstructor;
  };
  const Constructor = browser.SpeechRecognition ?? browser.webkitSpeechRecognition;
  const [listening, setListening] = useState(false);
  const [transcript, setTranscript] = useState("");
  const [seconds, setSeconds] = useState(0);
  const [error, setError] = useState<string | null>(null);
  const recognition = useRef<Recognition | null>(null);
  const heard = useRef("");
  const receive = useRef(onText);
  receive.current = onText;

  const stop = () => recognition.current?.stop();

  useEffect(() => {
    if (disabled) recognition.current?.abort();
  }, [disabled]);

  useEffect(() => {
    if (!listening) return undefined;
    setSeconds(0);
    const timer = setInterval(() => setSeconds((current) => current + 1), 1000);
    return () => clearInterval(timer);
  }, [listening]);

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
    heard.current = "";
    setTranscript("");
    current.lang = navigator.language;
    current.continuous = true;
    current.interimResults = true;
    current.onresult = (event) => {
      // A continuous session lists every result since it started, so the whole text is rebuilt.
      heard.current = Array.from(event.results, (result) => result[0].transcript.trim())
        .filter(Boolean)
        .join(" ");
      setTranscript(heard.current);
    };
    current.onerror = (event) => {
      if (event.error !== "aborted" && event.error !== "no-speech") {
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
        setTranscript("");
        if (heard.current) receive.current(heard.current);
        heard.current = "";
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

  return { supported: !!Constructor, listening, transcript, seconds, error, toggle, stop };
}
