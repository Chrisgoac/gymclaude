import { it, expect } from 'vitest';
import { useState } from 'react';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { DialogProvider, useDialogs } from '@/components/ui/dialog-provider';

function Harness() {
  const { confirm, prompt } = useDialogs();
  const [out, setOut] = useState('-');
  return (
    <>
      <button onClick={async () => setOut(`c:${await confirm({ titulo: '¿Seguro?' })}`)}>doConfirm</button>
      <button onClick={async () => setOut(`p:${await prompt({ titulo: 'Nombre', valorInicial: 'A' })}`)}>doPrompt</button>
      <span>out:{out}</span>
    </>
  );
}

const setup = () => render(<DialogProvider><Harness /></DialogProvider>);

it('confirm devuelve true al aceptar', async () => {
  setup();
  await userEvent.click(screen.getByText('doConfirm'));
  await userEvent.click(await screen.findByRole('button', { name: 'Aceptar' }));
  expect(await screen.findByText('out:c:true')).toBeInTheDocument();
});

it('confirm devuelve false al cancelar', async () => {
  setup();
  await userEvent.click(screen.getByText('doConfirm'));
  await userEvent.click(await screen.findByRole('button', { name: 'Cancelar' }));
  expect(await screen.findByText('out:c:false')).toBeInTheDocument();
});

it('prompt devuelve el texto editado al aceptar', async () => {
  setup();
  await userEvent.click(screen.getByText('doPrompt'));
  const input = await screen.findByRole('textbox');
  await userEvent.clear(input);
  await userEvent.type(input, 'Pierna');
  await userEvent.click(screen.getByRole('button', { name: 'Aceptar' }));
  expect(await screen.findByText('out:p:Pierna')).toBeInTheDocument();
});

it('prompt devuelve null al cancelar', async () => {
  setup();
  await userEvent.click(screen.getByText('doPrompt'));
  await screen.findByRole('textbox');
  await userEvent.click(screen.getByRole('button', { name: 'Cancelar' }));
  expect(await screen.findByText('out:p:null')).toBeInTheDocument();
});
