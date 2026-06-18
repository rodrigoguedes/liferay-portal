/**
 * SPDX-FileCopyrightText: (c) 2026 Liferay, Inc. https://liferay.com
 * SPDX-License-Identifier: LGPL-2.1-or-later OR LicenseRef-Liferay-DXP-EULA-2.0.0-2023-06
 */

import ClayAlert from '@clayui/alert';
import ClayButton from '@clayui/button';
import ClayForm, {ClayInput, ClaySelect} from '@clayui/form';
import {fetch} from 'frontend-js-web';
import React, {useEffect, useState} from 'react';

const TEXT_EMBEDDING_TASK_TYPE = 'text_embedding';

/**
 * Renders the "create a new Inference Endpoint" form dynamically from the
 * provider schemas Elasticsearch exposes (GET
 * /o/search/v1.0/embeddings/inference-services). Selecting a provider renders
 * exactly the fields it requires for the text_embedding task: sensitive fields
 * become password inputs and integer fields become number inputs. On submit it
 * creates the endpoint (POST /o/search/v1.0/embeddings/inference-endpoints)
 * under the administrator-chosen name and reports the created id.
 */
export default function InferenceEndpointCreationForm({
	namespace = '',
	onInferenceEndpointCreated,
}) {
	const [fieldValues, setFieldValues] = useState({});
	const [inferenceId, setInferenceId] = useState('');
	const [inferenceServices, setInferenceServices] = useState([]);
	const [loading, setLoading] = useState(false);
	const [message, setMessage] = useState({}); // {text, type}
	const [selectedService, setSelectedService] = useState('');

	useEffect(() => {
		fetch('/o/search/v1.0/embeddings/inference-services', {
			headers: new Headers({
				'Accept': 'application/json',
				'Accept-Language': Liferay.ThemeDisplay.getBCP47LanguageId(),
			}),
		})
			.then((response) => response.json())
			.then((responseData) => {
				const items = responseData?.items || [];

				setInferenceServices(items);

				if (items.length) {
					setSelectedService(items[0].service);
				}
			})
			.catch(() => {
				setMessage({
					text: Liferay.Language.get(
						'unable-to-load-the-list-of-inference-providers'
					),
					type: 'danger',
				});
			});
	}, []);

	const _getVisibleInferenceServiceFields = () => {
		const inferenceService = inferenceServices.find(
			(service) => service.service === selectedService
		);

		if (!inferenceService) {
			return [];
		}

		return (inferenceService.inferenceServiceFields || []).filter(
			(inferenceServiceField) =>
				(inferenceServiceField.supportedTaskTypes || []).includes(
					TEXT_EMBEDDING_TASK_TYPE
				)
		);
	};

	const _handleCreateButtonClick = () => {
		setLoading(true);
		setMessage({});

		fetch('/o/search/v1.0/embeddings/inference-endpoints', {
			body: JSON.stringify({
				inferenceId,
				service: selectedService,
				serviceSettings: fieldValues,
			}),
			headers: new Headers({
				'Accept': 'application/json',
				'Accept-Language': Liferay.ThemeDisplay.getBCP47LanguageId(),
				'Content-Type': 'application/json',
			}),
			method: 'POST',
		})
			.then((response) => response.json())
			.then((responseData) => {
				if (responseData.inferenceId) {
					setMessage({
						text: Liferay.Language.get(
							'the-inference-endpoint-was-created-successfully'
						),
						type: 'success',
					});

					if (onInferenceEndpointCreated) {
						onInferenceEndpointCreated(responseData.inferenceId);
					}
				}
				else {
					setMessage({
						text:
							responseData.message ||
							Liferay.Language.get(
								'an-unexpected-error-occurred'
							),
						type: 'danger',
					});
				}
			})
			.catch(() => {
				setMessage({
					text: Liferay.Language.get('an-unexpected-error-occurred'),
					type: 'danger',
				});
			})
			.finally(() => {
				setLoading(false);
			});
	};

	return (
		<div className="inference-endpoint-creation-form mt-3">
			<h4 className="sheet-subtitle">
				{Liferay.Language.get('create-an-inference-endpoint')}
			</h4>

			<ClayForm.Group>
				<label htmlFor={`${namespace}inferenceEndpointName`}>
					{Liferay.Language.get('inference-endpoint')}

					<span className="reference-mark text-warning">*</span>
				</label>

				<ClayInput
					id={`${namespace}inferenceEndpointName`}
					onChange={(event) => setInferenceId(event.target.value)}
					type="text"
					value={inferenceId}
				/>
			</ClayForm.Group>

			<ClayForm.Group>
				<label htmlFor={`${namespace}inferenceServiceSelector`}>
					{Liferay.Language.get('text-embedding-provider')}
				</label>

				<ClaySelect
					aria-label={Liferay.Language.get('text-embedding-provider')}
					id={`${namespace}inferenceServiceSelector`}
					onChange={(event) => {
						setSelectedService(event.target.value);
						setFieldValues({});
					}}
					value={selectedService}
				>
					{inferenceServices.map((inferenceService) => (
						<ClaySelect.Option
							key={inferenceService.service}
							label={inferenceService.name}
							value={inferenceService.service}
						/>
					))}
				</ClaySelect>
			</ClayForm.Group>

			{_getVisibleInferenceServiceFields().map(
				(inferenceServiceField) => (
					<ClayForm.Group key={inferenceServiceField.key}>
						<label
							htmlFor={`${namespace}${inferenceServiceField.key}`}
						>
							{inferenceServiceField.label}

							{inferenceServiceField.required && (
								<span className="reference-mark text-warning">
									*
								</span>
							)}
						</label>

						<ClayInput
							id={`${namespace}${inferenceServiceField.key}`}
							onChange={(event) =>
								setFieldValues({
									...fieldValues,
									[inferenceServiceField.key]:
										event.target.value,
								})
							}
							type={
								inferenceServiceField.sensitive
									? 'password'
									: inferenceServiceField.type === 'int'
										? 'number'
										: 'text'
							}
							value={fieldValues[inferenceServiceField.key] || ''}
						/>

						{inferenceServiceField.description && (
							<ClayForm.Text>
								{inferenceServiceField.description}
							</ClayForm.Text>
						)}
					</ClayForm.Group>
				)
			)}

			{!!message.text && (
				<ClayAlert className="mt-2" displayType={message.type}>
					{message.text}
				</ClayAlert>
			)}

			<ClayButton
				disabled={loading || !inferenceId || !selectedService}
				displayType="secondary"
				onClick={_handleCreateButtonClick}
			>
				{Liferay.Language.get('create-an-inference-endpoint')}
			</ClayButton>
		</div>
	);
}
